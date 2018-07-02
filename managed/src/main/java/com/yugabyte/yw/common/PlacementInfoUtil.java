// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import org.joda.time.DateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ClusterType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.NodeInstance;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementCloud;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion;
import play.libs.Json;

import static com.yugabyte.yw.common.Util.toBeAddedAzUuidToNumNodes;


public class PlacementInfoUtil {
  public static final Logger LOG = LoggerFactory.getLogger(PlacementInfoUtil.class);

  // This is the maximum number of subnets that the masters can be placed across, and need to be an
  // odd number for consensus to work.
  private static final int maxMasterSubnets = 3;

  // List of replication factors supported currently.
  private static final List<Integer> supportedRFs = ImmutableList.of(1, 3, 5, 7);

  // Constants used to determine tserver, master, and node liveness.
  public static final String UNIVERSE_ALIVE_METRIC = "node_up";

  // Mode of node distribution across the given AZ configuration.
  enum ConfigureNodesMode {
    NEW_CONFIG,                       // Round robin nodes across server chosen AZ placement.
    UPDATE_CONFIG_FROM_USER_INTENT,   // Use numNodes from userIntent with user chosen AZ's.
    UPDATE_CONFIG_FROM_PLACEMENT_INFO, // Use the placementInfo as per user chosen AZ distribution.
    NEW_CONFIG_FROM_PLACEMENT_INFO   // Create a move configuration using placement info
  }

  /**
   * Method to check whether the affinitized leaders info changed between the old and new
   * placements. If an AZ is present in the new placement but not the old or vice versa,
   * returns false.
   *
   * @param oldPlacementInfo Placement for the previous state of the Cluster.
   * @param newPlacementInfo Desired placement for the Cluster.
   * @return True if the affinitized leader info has changed and there are no new AZs, else false.
   */
  public static boolean didAffinitizedLeadersChange(PlacementInfo oldPlacementInfo,
                                                    PlacementInfo newPlacementInfo) {

    // Map between the old placement's AZs and the affinitized leader info.
    HashMap<UUID, Boolean> oldAZMap = new HashMap<UUID, Boolean>();

    for (PlacementCloud oldCloud : oldPlacementInfo.cloudList) {
      for (PlacementRegion oldRegion : oldCloud.regionList) {
        for (PlacementAZ oldAZ : oldRegion.azList) {
          oldAZMap.put(oldAZ.uuid, oldAZ.isAffinitized);
        }
      }
    }

    for (PlacementCloud newCloud : newPlacementInfo.cloudList) {
      for (PlacementRegion newRegion : newCloud.regionList) {
        for (PlacementAZ newAZ : newRegion.azList) {
          if (!oldAZMap.containsKey(newAZ.uuid)) {
            return false;
          }
          if (oldAZMap.get(newAZ.uuid) != newAZ.isAffinitized) {
            // affinitized leader info has changed, return true.
            return true;
          }
        }
      }
    }
    // No affinitized leader info has changed, return false.
    return false;
  }

  /**
   * Returns the PlacementAZ object with the specified UUID if exists in the provided PlacementInfo,
   * else null.
   *
   * @param placementInfo PlacementInfo object to look through for the desired PlacementAZ.
   * @param azUUID        UUID of the PlacementAZ to look for.
   * @return The specified PlacementAZ if it exists, else null.
   */
  private static PlacementAZ findPlacementAzByUuid(PlacementInfo placementInfo, UUID azUUID) {
    for (PlacementCloud cloud : placementInfo.cloudList) {
      for (PlacementRegion region : cloud.regionList) {
        for (PlacementAZ az : region.azList) {
          if (az.uuid.equals(azUUID)) {
            return az;
          }
        }
      }
    }
    return null;
  }

  /**
   * Method to check if the given set of nodes match the placement info AZ to detect pure
   * expand/shrink type.
   * The following cases are _not_ considered Expand/Shrink and will be treated as full move.
   * USWest1A - 3 , USWest1B - 1 => USWest1A - 2, USWest1B - 1  IFF all 3 in USWest1A are Masters.
   * USWest1A - 3, USWest1B - 3 => USWest1A - 6.
   * Basically any change in AZ will be treated as full move.
   * Only if number of nodes is increased/decreased and only if there are enough (non-master)
   * tserver only nodes to accomodate the change will it be treated as pure expand/shrink.
   * In Edit case is that the existing user intent should also be compared to new intent.
   * And for both edit and create, we need to compare previously chosen AZ's against the new
   * placementInfo.
   *
   * @param oldParams   Not null iff it is an edit op, so need to honor existing nodes/tservers.
   * @param newParams   Current user task and placement info with user proposed AZ distribution.
   * @param cluster     Cluster to check.
   * @return If the number of nodes only are changed across AZ's in placement or if userIntent
   *         node count only changes, returns that configure mode. Else defaults to new config.
   */
  private static ConfigureNodesMode getPureExpandOrShrinkMode(UniverseDefinitionTaskParams oldParams,
                                                              UniverseDefinitionTaskParams newParams,
                                                              Cluster cluster) {
    boolean isEditUniverse = oldParams != null;
    PlacementInfo newPlacementInfo = cluster.placementInfo;
    UserIntent newIntent = cluster.userIntent;
    Collection<NodeDetails> nodeDetailsSet = isEditUniverse ?
            oldParams.getNodesInCluster(cluster.uuid) :
            newParams.getNodesInCluster(cluster.uuid);

    // If it's an EditUniverse operation, check if old and new intents are equal.
    if (isEditUniverse) {
      UserIntent existingIntent = oldParams.getClusterByUuid(cluster.uuid).userIntent;
      LOG.info("Comparing task '{}' and existing '{}' intents.", newIntent, existingIntent);
      UserIntent tempIntent = newIntent.clone();
      tempIntent.numNodes = existingIntent.numNodes;
      if (!tempIntent.equals(existingIntent) || newIntent.numNodes == existingIntent.numNodes) {
        return ConfigureNodesMode.NEW_CONFIG;
      }
    }

    // Check if at least one AZ's num nodes count has changed
    boolean atLeastOneCountChanged = false;
    Map<UUID, Integer> azUuidToNumNodes = getAzUuidToNumNodes(nodeDetailsSet);
    for (UUID azUuid : azUuidToNumNodes.keySet()) {
      PlacementAZ az = findPlacementAzByUuid(newPlacementInfo, azUuid);
      if (az == null) {
        LOG.info("AZ {} not found in placement, so not pure expand/shrink.", azUuid);
        return ConfigureNodesMode.NEW_CONFIG;
      }
      int numTservers = findCountActiveTServerOnlyInAZ(nodeDetailsSet, azUuid);
      int azDifference = az.numNodesInAZ - azUuidToNumNodes.get(azUuid);
      LOG.info("AZ {} check, azNum={}, azDiff={}, numTservers={}.", az.name, az.numNodesInAZ,
              azDifference, numTservers);
      if (azDifference != 0) {
        atLeastOneCountChanged = true;
      }
      if (isEditUniverse && azDifference < 0  && -azDifference > numTservers) {
        return ConfigureNodesMode.NEW_CONFIG;
      }
    }

    // Log some information about the placement and type of edit/create we're doing.
    int placementCount = getNodeCountInPlacement(newPlacementInfo);
    if (isEditUniverse) {
      LOG.info("Edit taskNumNodes={}, placementNumNodes={}, numNodes={}.", newIntent.numNodes,
              placementCount, nodeDetailsSet.size());
    } else {
      LOG.info("Create taskNumNodes={}, placementNumNodes={}.", newIntent.numNodes, placementCount);
    }

    // If we made it this far, then we are in a pure expand/shrink mode. Check if the UserIntent
    // numNodes matches the sum of nodes in the placement and count changed on the per AZ placement.
    // Else, number of nodes in the UserIntent should be honored and the perAZ node count ignored.
    ConfigureNodesMode mode = ConfigureNodesMode.NEW_CONFIG;
    if (newIntent.numNodes == placementCount && atLeastOneCountChanged) {
      mode = ConfigureNodesMode.UPDATE_CONFIG_FROM_PLACEMENT_INFO;
    } else if (newIntent.numNodes != placementCount) {
      mode = ConfigureNodesMode.UPDATE_CONFIG_FROM_USER_INTENT;
    }
    LOG.info("Pure expand/shrink in {} mode.", mode);
    return mode;
  }

  // Assumes that there is only single provider across all nodes in a given set.
  private static UUID getProviderUUID(Collection<NodeDetails> nodes, UUID placementUuid) {
    if (nodes == null || nodes.isEmpty()) {
      return null;
    }
    NodeDetails node = nodes.stream()
            .filter(n -> n.isInPlacement(placementUuid))
            .findFirst()
            .orElse(null);
    return (node == null) ? null : AvailabilityZone.find.byId(node.azUuid).region.provider.uuid;
  }

  private static Set<UUID> getAllRegionUUIDs(Collection<NodeDetails> nodes, UUID placementUuid) {
    Set<UUID> nodeRegionSet = new HashSet<>();
    nodes.stream()
            .filter(n -> n.isInPlacement(placementUuid))
            .forEach(n -> nodeRegionSet.add(AvailabilityZone.find.byId(n.azUuid).region.uuid));
    return nodeRegionSet;
  }

  /**
   * Helper API to check if the list of regions is the same in existing nodes of the placement
   * and the new userIntent's region list.
   *
   * @param cluster The current user proposed cluster.
   * @param nodes The set of nodes used to compare the current region layout.
   * @return true if the provider or region list changed. false if neither changed.
   */
  private static boolean isProviderOrRegionChange(Cluster cluster, Collection<NodeDetails> nodes) {
    // Initial state. No nodes have been requested, so nothing has changed.
    if (nodes.isEmpty()) {
      return false;
    }

    // Compare Providers.
    UUID intentProvider = getProviderUUID(nodes, cluster.uuid);
    UUID nodeProvider = cluster.placementInfo.cloudList.get(0).uuid;
    if (!intentProvider.equals(nodeProvider)) {
      LOG.info("Provider in intent {} is different from provider in existing nodes {} in cluster {}.",
              intentProvider, nodeProvider, cluster.uuid);
      return true;
    }

    // Compare Regions.
    Set<UUID> nodeRegionSet = getAllRegionUUIDs(nodes, cluster.uuid);
    Set<UUID> intentRegionSet = new HashSet<>(cluster.userIntent.regionList);
    LOG.info("Intended Regions {} vs existing Regions {} in cluster {}.",
            intentRegionSet, nodeRegionSet, cluster.uuid);
    return !intentRegionSet.equals(nodeRegionSet);
  }

  public static int getNodeCountInPlacement(PlacementInfo placementInfo) {
    int count = 0;
    for (PlacementCloud cloud : placementInfo.cloudList) {
      for (PlacementRegion region : cloud.regionList) {
        for (PlacementAZ az : region.azList) {
          count += az.numNodesInAZ;
        }
      }
    }
    return count;
  }

  // Helper API to catch duplicated node names in the given set of nodes.
  public static void ensureUniqueNodeNames(Collection<NodeDetails> nodes) throws RuntimeException {
    boolean foundDups = false;
    Set<String> nodeNames = new HashSet<String>();

    for (NodeDetails node : nodes) {
      if (nodeNames.contains(node.nodeName)) {
        LOG.error("Duplicate nodeName {}.", node.nodeName);
        foundDups = true;
      } else {
        nodeNames.add(node.nodeName);
      }
    }

    if (foundDups) {
      throw new RuntimeException("Found duplicated node names, error info in logs just above.");
    }
  }

  // Helper API to order the read-only clusters for naming purposes.
  public static void populateClusterIndices(UniverseDefinitionTaskParams taskParams) {
    for (Cluster cluster : taskParams.getReadOnlyClusters()) {
      if (cluster.index == 0) {
        // The cluster index isn't set, which means its a new cluster, set the cluster
        // index and increment the global max.
        cluster.index = taskParams.nextClusterIndex++;
      }
    }
  }

  /**
   * Helper API to set some of the non user supplied information in task params.
   * @param taskParams : Universe task params.
   * @param customerId : Current customer's id.
   * @param placementUuid : uuid of the cluster user is working on.
   */
  public static void updateUniverseDefinition(UniverseDefinitionTaskParams taskParams,
                                              Long customerId,
                                              UUID placementUuid) {

    Cluster cluster = taskParams.getClusterByUuid(placementUuid);

    // Create node details set if needed.
    if (taskParams.nodeDetailsSet == null) {
      taskParams.nodeDetailsSet = new HashSet<>();
    }

    Universe universe = null;
    if (taskParams.universeUUID == null) {
      taskParams.universeUUID = UUID.randomUUID();
    } else {
      try {
        universe = Universe.get(taskParams.universeUUID);
      } catch (Exception e) {
        LOG.info("Universe with UUID {} not found, configuring new universe.",
                 taskParams.universeUUID);
      }
    }

    int numRO = taskParams.getReadOnlyClusters().size();
    int numExistingROs =
        universe == null ? 0 : universe.getUniverseDetails().getReadOnlyClusters().size();
    boolean readOnlyClusterCreate = numRO == 1 && numExistingROs == 0;
    boolean readOnlyClusterEdit = numRO == 1 && numExistingROs >= 1;
    LOG.info("newRO={}, existingRO={}, rocc={}, roce={}.", numRO, numExistingROs,
             readOnlyClusterCreate, readOnlyClusterEdit);
    String universeName = universe == null ?
        taskParams.getPrimaryCluster().userIntent.universeName :
        universe.getUniverseDetails().getPrimaryCluster().userIntent.universeName;

    // Compose a unique name for the nodes in the universe.
    taskParams.nodePrefix = Util.getNodePrefix(customerId, universeName);

    ConfigureNodesMode mode;
    boolean isPrimaryClusterEdit = (universe != null) && !readOnlyClusterEdit;
    // If no placement info, and if this is the first primary or readonly cluster create attempt,
    // choose a new placement.
    if (cluster.placementInfo == null && (!isPrimaryClusterEdit || readOnlyClusterCreate)) {
      taskParams.nodeDetailsSet.removeIf(n -> n.isInPlacement(placementUuid));
      cluster.placementInfo = getPlacementInfo(cluster.userIntent);
      LOG.info("Placement created={}.", cluster.placementInfo);
      configureNodeStates(taskParams, null, ConfigureNodesMode.NEW_CONFIG, cluster);
      return;
    }

    // Verify the provided edit parameters, if in edit universe case, and get the mode.
    // Otherwise it is a primary or readonly cluster creation phase changes.
    LOG.info("Placement={}, numNodes={}, AZ={}.", cluster.placementInfo,
             taskParams.nodeDetailsSet.size(), taskParams.userAZSelected);
    if (isPrimaryClusterEdit && !readOnlyClusterCreate && !readOnlyClusterEdit) {
      // If user AZ Selection is made for Edit get a new configuration from placement info
      if (taskParams.userAZSelected) {
        mode = ConfigureNodesMode.NEW_CONFIG_FROM_PLACEMENT_INFO;
        configureNodeStates(taskParams, universe, mode, cluster);
        return;
      }
      Cluster oldCluster = universe.getUniverseDetails().getPrimaryCluster();
      verifyEditParams(oldCluster, cluster);

      if (didAffinitizedLeadersChange(oldCluster.placementInfo, cluster.placementInfo)) {
        mode = ConfigureNodesMode.UPDATE_CONFIG_FROM_PLACEMENT_INFO;
      } else {
        mode = getPureExpandOrShrinkMode(universe.getUniverseDetails(), taskParams, cluster);
        taskParams.nodeDetailsSet.clear();
        taskParams.nodeDetailsSet.addAll(universe.getNodes());
      }
    } else {
      mode = getPureExpandOrShrinkMode(readOnlyClusterEdit ? universe.getUniverseDetails(): null,
                                       taskParams, cluster);
    }

    // If not a pure expand/shrink, we will pick a new set of nodes. If the provider or region list
    // changed, we will pick a new placement (i.e full move, create universe).
    if (!readOnlyClusterCreate && mode == ConfigureNodesMode.NEW_CONFIG) {
      if (isProviderOrRegionChange(cluster,
              (isPrimaryClusterEdit || readOnlyClusterEdit) ?
              universe.getNodes() : taskParams.nodeDetailsSet)) {
        LOG.info("Provider or region changed, getting new placement info for full move.");
        cluster.placementInfo = getPlacementInfo(cluster.userIntent);
      } else {
        LOG.info("Performing full move with existing placement info.");
      }
      taskParams.nodeDetailsSet.removeIf(n -> n.isInPlacement(placementUuid));
    }

    // Compute the node states that should be configured for this operation.
    configureNodeStates(taskParams, universe, mode, cluster);

    LOG.info("Set of nodes after node configure:{}.", taskParams.nodeDetailsSet);
    ensureUniqueNodeNames(taskParams.nodeDetailsSet.stream()
                                                   .filter(n -> n.isInPlacement(placementUuid))
                                                   .collect(Collectors.toSet()));
    LOG.info("Placement info:{}.", cluster.placementInfo);
  }

  /**
   * Method checks if enough nodes have been configured to satiate the userIntent for an OnPrem configuration
   * @param taskParams Universe task params.
   * @param cluster Cluster to be checked.
   * @return True if provider type is not OnPrem or if enough nodes have been configured for intent, false otherwise
   */
  public static boolean checkIfNodeParamsValid(UniverseDefinitionTaskParams taskParams, Cluster cluster) {
    if (cluster.userIntent.providerType != CloudType.onprem) {
      return true;
    }
    UserIntent userIntent = cluster.userIntent;
    String instanceType = userIntent.instanceType;
    Set<NodeDetails> clusterNodes = taskParams.getNodesInCluster(cluster.uuid);
    // If NodeDetailsSet is null, do a high level check whether number of nodes in given config is present
    if (clusterNodes == null || clusterNodes.isEmpty()) {
      int totalNodesConfiguredInRegionList = 0;
      // Check if number of nodes in the user intent is greater than number of nodes configured for given instance type
      for (UUID regionUUID: userIntent.regionList) {
        for (AvailabilityZone az: Region.get(regionUUID).zones) {
          totalNodesConfiguredInRegionList += NodeInstance.listByZone(az.uuid, instanceType).size();
        }
      }
      if (totalNodesConfiguredInRegionList < userIntent.numNodes) {
        LOG.error("Not enough nodes, required: {} nodes, configured: {} nodes", userIntent.numNodes, totalNodesConfiguredInRegionList);
        return false;
      }
    } else {
      // If NodeDetailsSet is non-empty verify that the node/az combo is valid
      for (Map.Entry<UUID, Integer> entry : toBeAddedAzUuidToNumNodes(clusterNodes).entrySet()) {
        UUID azUUID = entry.getKey();
        int numNodesToBeAdded = entry.getValue().intValue();
        if (numNodesToBeAdded > NodeInstance.listByZone(azUUID, instanceType).size()) {
          LOG.error("Not enough nodes configured for given AZ/Instance type combo, " +
                          "required {} found {} in AZ {} for Instance type {}", numNodesToBeAdded, NodeInstance.listByZone(azUUID, instanceType).size(),
                  azUUID, instanceType);
          return false;
        }
      }
    }
    return true;
  }

  public static void updatePlacementInfo(Collection<NodeDetails> nodes,
                                         PlacementInfo placementInfo) {
    if (nodes != null && placementInfo != null) {
      Map<UUID, Integer> azUuidToNumNodes = getAzUuidToNumNodes(nodes);
      for (int cIdx = 0; cIdx < placementInfo.cloudList.size(); cIdx++) {
        PlacementCloud cloud = placementInfo.cloudList.get(cIdx);
        for (int rIdx = 0; rIdx < cloud.regionList.size(); rIdx++) {
          PlacementRegion region = cloud.regionList.get(rIdx);
          for (int azIdx = 0; azIdx < region.azList.size(); azIdx++) {
            PlacementAZ az = region.azList.get(azIdx);
            if (azUuidToNumNodes.get(az.uuid) != null) {
              az.numNodesInAZ = azUuidToNumNodes.get(az.uuid);
            } else {
              region.azList.remove(az);
              azIdx--;
            }
          }
          if (region.azList.isEmpty()) {
            cloud.regionList.remove(region);
            rIdx--;
          }
        }
      }
    }
  }

  public static Set<NodeDetails> getMastersToBeRemoved(Set<NodeDetails> nodeDetailsSet) {
    return getServersToBeRemoved(nodeDetailsSet, ServerType.MASTER);
  }

  public static Set<NodeDetails> getTserversToBeRemoved(Set<NodeDetails> nodeDetailsSet) {
    return getServersToBeRemoved(nodeDetailsSet, ServerType.TSERVER);
  }

  private static Set<NodeDetails> getServersToBeRemoved(Set<NodeDetails> nodeDetailsSet,
                                                        ServerType serverType) {
    Set<NodeDetails> servers = new HashSet<NodeDetails>();

    for (NodeDetails node : nodeDetailsSet) {
      if (node.state == NodeDetails.NodeState.ToBeRemoved &&
          (serverType == ServerType.MASTER && node.isMaster ||
           serverType == ServerType.TSERVER && node.isTserver)) {
        servers.add(node);
      }
    }

    return servers;
  }

  public static Set<NodeDetails> getNodesToBeRemoved(Set<NodeDetails> nodeDetailsSet) {
    Set<NodeDetails> servers = new HashSet<NodeDetails>();

    for (NodeDetails node : nodeDetailsSet) {
      if (node.state == NodeDetails.NodeState.ToBeRemoved) {
        servers.add(node);
      }
    }

    return servers;
  }

  public static Set<NodeDetails> getNodesToProvision(Set<NodeDetails> nodeDetailsSet) {
    return getServersToProvision(nodeDetailsSet, ServerType.EITHER);
  }

  public static Set<NodeDetails> getMastersToProvision(Set<NodeDetails> nodeDetailsSet) {
    return getServersToProvision(nodeDetailsSet, ServerType.MASTER);
  }

  public static Set<NodeDetails> getTserversToProvision(Set<NodeDetails> nodeDetailsSet) {
    return getServersToProvision(nodeDetailsSet, ServerType.TSERVER);
  }

  private static Set<NodeDetails> getServersToProvision(Set<NodeDetails> nodeDetailsSet,
                                                        ServerType serverType) {
    Set<NodeDetails> nodesToProvision = new HashSet<NodeDetails>();
    for (NodeDetails node : nodeDetailsSet) {
      if (node.state == NodeDetails.NodeState.ToBeAdded &&
              (serverType == ServerType.EITHER ||
                      serverType == ServerType.MASTER && node.isMaster ||
                      serverType == ServerType.TSERVER && node.isTserver)) {
        nodesToProvision.add(node);
      }
    }
    return nodesToProvision;
  }

  private static class AZInfo {
    public AZInfo(boolean affinitized, int numNodes) {
      isAffinitized = affinitized;
      numNodesInAZ = numNodes;
    }

    public boolean isAffinitized;
    public int numNodesInAZ;
  }

  // Helper function to check if the old placement and new placement after edit
  // are the same.
  private static boolean isSamePlacement(PlacementInfo oldPlacementInfo,
                                         PlacementInfo newPlacementInfo) {
    HashMap<UUID, AZInfo> oldAZMap = new HashMap<UUID, AZInfo>();

    for (PlacementCloud oldCloud : oldPlacementInfo.cloudList) {
      for (PlacementRegion oldRegion : oldCloud.regionList) {
        for (PlacementAZ oldAZ : oldRegion.azList) {
          oldAZMap.put(oldAZ.uuid, new AZInfo(oldAZ.isAffinitized, oldAZ.numNodesInAZ));
        }
      }
    }

    for (PlacementCloud newCloud : newPlacementInfo.cloudList) {
      for (PlacementRegion newRegion : newCloud.regionList) {
        for (PlacementAZ newAZ : newRegion.azList) {
          if (!oldAZMap.containsKey(newAZ.uuid)) {
            return false;
          }
          AZInfo azInfo = oldAZMap.get(newAZ.uuid);
          if (azInfo.isAffinitized != newAZ.isAffinitized ||
                  azInfo.numNodesInAZ != newAZ.numNodesInAZ) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * Verify that the planned changes for an Edit Universe operation are allowed.
   *
   * @param oldCluster    The current (soon to be old) version of a cluster.
   * @param newCluster    The intended next version of the same cluster.
   */
  private static void verifyEditParams(Cluster oldCluster, Cluster newCluster) {
    UserIntent existingIntent = oldCluster.userIntent;
    UserIntent userIntent = newCluster.userIntent;
    LOG.info("old intent: {}", existingIntent.toString());
    LOG.info("new intent: {}", userIntent.toString());
    // Error out if no fields are modified.
    if (userIntent.equals(existingIntent) &&
        isSamePlacement(oldCluster.placementInfo, newCluster.placementInfo)) {
      LOG.error("No fields were modified for edit universe.");
      throw new IllegalArgumentException("Invalid operation: At least one field should be " +
              "modified for editing the universe.");
    }

    // Rule out some of the universe changes that we do not allow (they can be enabled as needed).
    if (existingIntent.replicationFactor != userIntent.replicationFactor) {
      LOG.error("Replication factor cannot be changed from {} to {}",
              existingIntent.replicationFactor, userIntent.replicationFactor);
      throw new UnsupportedOperationException("Replication factor cannot be modified.");
    }

    if (!existingIntent.universeName.equals(userIntent.universeName)) {
      LOG.error("universeName cannot be changed from {} to {}",
              existingIntent.universeName, userIntent.universeName);
      throw new UnsupportedOperationException("Universe name cannot be modified.");
    }

    if (!existingIntent.provider.equals(userIntent.provider)) {
      LOG.error("Provider cannot be changed from {} to {}",
              existingIntent.provider, userIntent.provider);
      throw new UnsupportedOperationException("Provider cannot be modified.");
    }

    if (existingIntent.providerType != userIntent.providerType) {
      LOG.error("Provider type cannot be changed from {} to {}",
              existingIntent.providerType, userIntent.providerType);
      throw new UnsupportedOperationException("providerType cannot be modified.");
    }

    verifyNodesAndRF(userIntent.numNodes, userIntent.replicationFactor);
  }

  // Helper API to verify number of nodes and replication factor requirements.
  public static void verifyNodesAndRF(int numNodes, int replicationFactor) {
    // We only support a replication factor of 1,3,5,7.
    if (!supportedRFs.contains(replicationFactor)) {
      String errMsg = String.format("Replication factor %d not allowed, must be one of %s.",
          replicationFactor, Joiner.on(',').join(supportedRFs));
      LOG.error(errMsg);
      throw new UnsupportedOperationException(errMsg);
    }

    // If not a fresh create, must have at least as many nodes as the replication factor.
    if (numNodes > 0 && numNodes < replicationFactor) {
      String errMsg = String.format("Number of nodes %d cannot be less than the replication " +
          " factor %d.", numNodes, replicationFactor);
      LOG.error(errMsg);
      throw new UnsupportedOperationException(errMsg);
    }
  }

  // Helper API to sort an given map in ascending order of its values and return the same.
  private static LinkedHashMap<UUID, Integer> sortByValues(Map<UUID, Integer> map,
                                                           boolean isAscending) {
    List<Map.Entry<UUID, Integer>> list = new LinkedList<Map.Entry<UUID, Integer>>(map.entrySet());
    if (isAscending) {
      Collections.sort(list, new Comparator<Map.Entry<UUID, Integer>>() {
        public int compare(Map.Entry<UUID, Integer> o1, Map.Entry<UUID, Integer> o2) {
          return (o1.getValue()).compareTo(o2.getValue());
        }
      });
    } else {
      Collections.sort(list, new Comparator<Map.Entry<UUID, Integer>>() {
        public int compare(Map.Entry<UUID, Integer> o1, Map.Entry<UUID, Integer> o2) {
          return (o2.getValue()).compareTo(o1.getValue());
        }
      });
    }
    LinkedHashMap<UUID, Integer> sortedHashMap = new LinkedHashMap<UUID, Integer>();
    for (Map.Entry<UUID, Integer> entry : list) {
      sortedHashMap.put(entry.getKey(), entry.getValue());
    }
    return sortedHashMap;
  }

  public enum Action {
    NONE,  // Just for initial/defaut value.
    ADD,   // A node has to be added at this placement indices combination.
    REMOVE // Remove the node at this placement indices combination.
  }
  // Structure for tracking the calculated placement indexes on cloud/region/az.
  private static class PlacementIndexes {
    public int cloudIdx = 0;
    public int regionIdx = 0;
    public int azIdx = 0;
    public Action action;

    public PlacementIndexes(int aIdx, int rIdx, int cIdx) {
      cloudIdx = cIdx;
      regionIdx= rIdx;
      azIdx = aIdx;
      action = Action.NONE;
    }

    public PlacementIndexes(int aIdx, int rIdx, int cIdx, boolean isAdd) {
      cloudIdx = cIdx;
      regionIdx= rIdx;
      azIdx = aIdx;
      action = isAdd ? Action.ADD : Action.REMOVE;
    }

    public String toString() {
      return "[" + cloudIdx + ":" + regionIdx + ":" + azIdx + ":" + action + "]";
    }
  }

  // Create the ordered (by increasing node count per AZ) list of placement indices in the
  // given placement info.
  private static LinkedHashSet<PlacementIndexes> findPlacementsOfAZUuid(Map<UUID, Integer> azUuids,
                                                                        Cluster cluster) {
    LinkedHashSet<PlacementIndexes> placements = new LinkedHashSet<PlacementIndexes>();
    CloudType cloudType = cluster.userIntent.providerType;
    String instanceType = cluster.userIntent.instanceType;
    for (UUID targetAZUuid : azUuids.keySet()) {
      int cIdx = 0;
      for (PlacementCloud cloud : cluster.placementInfo.cloudList) {
        int rIdx = 0;
        for (PlacementRegion region : cloud.regionList) {
          int aIdx = 0;
          for (PlacementAZ az : region.azList) {
            if (az.uuid.equals(targetAZUuid)) {
              UUID zoneUUID = region.azList.get(aIdx).uuid;
              if (cloudType.equals(CloudType.onprem)){
                List<NodeInstance> nodesInAZ = NodeInstance.listByZone(zoneUUID, instanceType);
                int numNodesConfigured = getNumNodesInAZInPlacement(placements, new PlacementIndexes(aIdx, rIdx, cIdx, true));
                if (numNodesConfigured < nodesInAZ.size()) {
                  placements.add(new PlacementIndexes(aIdx, rIdx, cIdx));
                  continue;
                }
              } else {
                placements.add(new PlacementIndexes(aIdx, rIdx, cIdx));
                continue;
              }
            }
            aIdx++;
          }
          rIdx++;
        }
        cIdx++;
      }
    }
    LOG.debug("Placement indexes {}.", placements);
    return placements;
  }

  // Helper function to compare a given index combo against list of placement indexes of current placement
  // to get number of nodes in current index
  private static int getNumNodesInAZInPlacement(LinkedHashSet<PlacementIndexes> indexes, PlacementIndexes index) {
    return (int) indexes.stream().filter(currentIndex -> (currentIndex.cloudIdx == index.cloudIdx
            && currentIndex.azIdx == index.azIdx && currentIndex.regionIdx == index.regionIdx)).count();
  }

  private static LinkedHashSet<PlacementIndexes> getBasePlacement(int numNodes,
                                                                  Cluster cluster) {
    LinkedHashSet<PlacementIndexes> placements = new LinkedHashSet<PlacementIndexes>();
    CloudType cloudType = cluster.userIntent.providerType;
    String instanceType = cluster.userIntent.instanceType;
    int count = 0;
    while (count < numNodes) {
      int cIdx = 0;
      for (PlacementCloud cloud : cluster.placementInfo.cloudList) {
        int rIdx = 0;
        for (PlacementRegion region : cloud.regionList) {
          for (int azIdx = 0; azIdx < region.azList.size(); azIdx++) {
            UUID zoneUUID = region.azList.get(azIdx).uuid;
            if (cloudType.equals(CloudType.onprem)){
              List<NodeInstance> nodesInAZ = NodeInstance.listByZone(zoneUUID, instanceType);
              int numNodesConfigured = getNumNodesInAZInPlacement(placements, new PlacementIndexes(azIdx, rIdx, cIdx, true));
              if (numNodesConfigured < nodesInAZ.size() && count < numNodes) {
                placements.add(new PlacementIndexes(azIdx, rIdx, cIdx, true /* isAdd */));
                LOG.info("Adding {}/{}/{} @ {}.", azIdx, rIdx, cIdx, count);
                count++;
              }
            } else {
              placements.add(new PlacementIndexes(azIdx, rIdx, cIdx, true /* isAdd */));
              LOG.info("Adding {}/{}/{} @ {}.", azIdx, rIdx, cIdx, count);
              count++;
            }
          }
          rIdx++;
        }
        cIdx++;
      }
    }
    LOG.info("Base placement indexes {} for {} nodes.", placements, numNodes);
    return placements;
  }

  public static Map<UUID, Integer> getAzUuidToNumNodes(PlacementInfo placement) {
    Map<UUID, Integer> azUuidToNumNodes = new HashMap<UUID, Integer>();

    for (PlacementCloud cloud : placement.cloudList) {
      for (PlacementRegion region : cloud.regionList) {
        for (PlacementAZ az : region.azList) {
          azUuidToNumNodes.put(az.uuid, az.numNodesInAZ);
        }
      }
    }

    LOG.info("Az placement map {}", azUuidToNumNodes);

    return azUuidToNumNodes;
  }

  public static Map<UUID, Integer> getAzUuidToNumNodes(Collection<NodeDetails> nodeDetailsSet) {
    // Get node count per azUuid in the current universe.
    Map<UUID, Integer> azUuidToNumNodes = new HashMap<UUID, Integer>();
    for (NodeDetails node : nodeDetailsSet) {
      UUID azUuid = node.azUuid;
      if (!azUuidToNumNodes.containsKey(azUuid)) {
        azUuidToNumNodes.put(azUuid, 0);
      }
      azUuidToNumNodes.put(azUuid, azUuidToNumNodes.get(azUuid) + 1);
    }

    LOG.info("Az Map {}", azUuidToNumNodes);

    return azUuidToNumNodes;
  }

  // Count number of active tserver-only nodes in the given AZ.
  private static int findCountActiveTServerOnlyInAZ(Collection<NodeDetails> nodeDetailsSet,
                                                    UUID targetAZUuid) {
    int numActiveServers = 0;
    for (NodeDetails node : nodeDetailsSet) {
      if (node.isActive() && !node.isMaster && node.isTserver && node.azUuid.equals(targetAZUuid)) {
        numActiveServers ++;
      }
    }
    return numActiveServers;
  }

  // Find a node running tserver only in the given AZ.
  private static NodeDetails findActiveTServerOnlyInAz(Collection<NodeDetails> nodeDetailsSet,
                                                       UUID targetAZUuid) {
    for (NodeDetails node : nodeDetailsSet) {
      if (node.isActive() && !node.isMaster && node.isTserver && node.azUuid.equals(targetAZUuid)) {
        return node;
      }
    }
    return null;
  }

  /**
   * Get new nodes per AZ that need to be added or removed for custom AZ placement scenarios.
   * Assign nodes as per AZ distribution delta between placementInfo and existing nodes and
   * save order of those indices.
   * @param placementInfo has the distribution of Nodes in each AZ.
   * @param nodes Set of currently allocated nodes.
   * @return set of indexes in which to provision the nodes.
   */
  private static LinkedHashSet<PlacementIndexes> getDeltaPlacementIndices(
      PlacementInfo placementInfo, Collection<NodeDetails> nodes) {
    LinkedHashSet<PlacementIndexes> placements = new LinkedHashSet<PlacementIndexes>();
    Map<UUID, Integer> azUuidToNumNodes = getAzUuidToNumNodes(nodes);

    for (int cIdx = 0; cIdx < placementInfo.cloudList.size(); cIdx++) {
      PlacementCloud cloud = placementInfo.cloudList.get(cIdx);
      for (int rIdx = 0; rIdx < cloud.regionList.size(); rIdx++) {
        PlacementRegion region = cloud.regionList.get(rIdx);
        for (int azIdx = 0; azIdx < region.azList.size(); azIdx++) {
          PlacementAZ az = region.azList.get(azIdx);
          int numDesired = az.numNodesInAZ;
          int numPresent = azUuidToNumNodes.containsKey(az.uuid) ? azUuidToNumNodes.get(az.uuid) : 0;
          LOG.info("AZ {} : Desired {}, present {}.", az.name, numDesired, numPresent);
          int numChange = Math.abs(numDesired - numPresent);
          while (numChange > 0) {
            placements.add(new PlacementIndexes(azIdx, rIdx, cIdx, numDesired > numPresent));
            numChange--;
          }
        }
      }
    }
    LOG.debug("Delta placement indexes {}.", placements);
    return placements;
  }

  /**
   * Remove a tserver-only node that belongs to the given AZ from the collection of nodes.
   * @param nodes        the list of nodes from which to choose the victim.
   * @param targetAZUuid AZ in which the node should be present.
   */
  private static void removeNodeInAZ(Collection<NodeDetails> nodes,
                                     UUID targetAZUuid) {
    Iterator<NodeDetails> nodeIter = nodes.iterator();
    while (nodeIter.hasNext()) {
      NodeDetails currentNode = nodeIter.next();
      if (currentNode.azUuid.equals(targetAZUuid) && !currentNode.isMaster) {
        nodeIter.remove();
        return;
      }
    }
  }

  /**
   * This method configures nodes for Edit case, with user specified placement info.
   * It supports the following combinations --
   * 1. Reset AZ, it result in a full move as new config is generated
   * 2. Any subsequent operation after a Reset AZ will be a full move since subsequent operations will build on reset
   * 3. Simple Node Count increase will result in an expand.
   * 4. Any Shrink scenario will be a full move. This is to prevent inconsistencies in cases where the node which is subtracted
   * is a master node vs. if it is not a master node which are indistinguishable from an AZ Selector pov.
   * 5. Multi to Single AZ ops will also be a full move operation since it involves shrinks
   * @param taskParams
   */
  public static void configureNodeEditUsingPlacementInfo(UniverseDefinitionTaskParams taskParams) {
    PlacementInfo primaryPlacementInfo = taskParams.getPrimaryCluster().placementInfo;
    Universe universe = Universe.get(taskParams.universeUUID);
    Cluster primaryCluster = universe.getUniverseDetails().getPrimaryCluster();
    Collection<NodeDetails> existingNodes = universe.getNodesInCluster(primaryCluster.uuid);

    // If placementInfo is null then user has chosen to Reset AZ config
    // Hence a new full move configuration is generated
    if (primaryPlacementInfo == null) {
      // Remove primary cluster nodes which will be added back in ToBeRemoved state
      taskParams.nodeDetailsSet.removeIf((NodeDetails nd) -> {
        return (nd.placementUuid.equals(primaryCluster.uuid));
      });

      taskParams.getPrimaryCluster().placementInfo = getPlacementInfo(taskParams.getPrimaryCluster().userIntent);
      configureDefaultNodeStates(taskParams.getPrimaryCluster(), taskParams.nodeDetailsSet,
              taskParams.nodePrefix, universe);
    } else {
      // In other operations we need to distinguish between expand and full-move.
      Map<UUID, Integer> requiredAZToNodeMap = getAzUuidToNumNodes(taskParams.getPrimaryCluster().placementInfo);
      Map<UUID, Integer> existingAZToNodeMap = getAzUuidToNumNodes(universe.getUniverseDetails().nodeDetailsSet);

      boolean isSimpleExpand = true;
      for (UUID requiredAZUUID: requiredAZToNodeMap.keySet()) {
        if (!existingAZToNodeMap.containsKey(requiredAZUUID) || requiredAZToNodeMap.get(requiredAZUUID) < existingAZToNodeMap.get(requiredAZUUID)) {
          isSimpleExpand = false;
          break;
        } else {
          existingAZToNodeMap.remove(requiredAZUUID);
        }
      }
      if (existingAZToNodeMap.size() > 0) {
        isSimpleExpand = false;
      }
      if (isSimpleExpand) {
        // If simple expand we can go in the configure using placement info path
        configureNodesUsingPlacementInfo(taskParams.getPrimaryCluster(),
                                         taskParams.nodeDetailsSet,
                                         taskParams.nodePrefix, true);
        // Break execution sequence because there are no nodes to be decomissioned
        return;
      } else {
        // If not simply create a nodeDetailsSet from the provided placement info.
        taskParams.nodeDetailsSet.clear();
        int startIndex = getNextIndexToConfigure(existingNodes);
        int iter = 0;
        LinkedHashSet<PlacementIndexes> placements = new LinkedHashSet<PlacementIndexes>();
        for (int cIdx = 0; cIdx < primaryPlacementInfo.cloudList.size(); cIdx++) {
          PlacementCloud cloud = primaryPlacementInfo.cloudList.get(cIdx);
          for (int rIdx = 0; rIdx < cloud.regionList.size(); rIdx++) {
            PlacementRegion region = cloud.regionList.get(rIdx);
            for (int azIdx = 0; azIdx < region.azList.size(); azIdx++) {
              PlacementAZ az = region.azList.get(azIdx);
              int numDesired = az.numNodesInAZ;

              int numChange = Math.abs(numDesired);
              // Add all new nodes in the tbe added state
              while (numChange > 0) {
                iter ++;
                placements.add(new PlacementIndexes(azIdx, rIdx, cIdx, true));
                NodeDetails nodeDetails =
                        createNodeDetailsWithPlacementIndex(taskParams.getPrimaryCluster(),
                            taskParams.nodePrefix, new PlacementIndexes(azIdx, rIdx, cIdx, true),
                            startIndex + iter);
                taskParams.nodeDetailsSet.add(nodeDetails);
                numChange--;
              }
            }
          }
        }
      }
    }
    LOG.info("Removing {} nodes.", existingNodes.size());
    for (NodeDetails node : existingNodes) {
      node.state = NodeDetails.NodeState.ToBeRemoved;
      taskParams.nodeDetailsSet.add(node);
    }
  }

  private static void configureNodesUsingPlacementInfo(Cluster cluster,
                                                       Collection<NodeDetails> nodes,
                                                       String nodePrefix,
                                                       boolean isEditUniverse) {
    LinkedHashSet<PlacementIndexes> indexes =
        getDeltaPlacementIndices(
            cluster.placementInfo,
            nodes.stream().filter(n -> n.placementUuid.equals(cluster.uuid)).collect(Collectors.toSet()));
    Set<NodeDetails> deltaNodesSet = new HashSet<NodeDetails>();
    int startIndex = getNextIndexToConfigure(nodes);
    int iter = 0;
    for (PlacementIndexes index : indexes) {
      if (index.action == Action.ADD) {
        NodeDetails nodeDetails =
            createNodeDetailsWithPlacementIndex(cluster, nodePrefix, index, startIndex + iter);
        deltaNodesSet.add(nodeDetails);
      } else if (index.action == Action.REMOVE) {
        PlacementCloud placementCloud = cluster.placementInfo.cloudList.get(index.cloudIdx);
        PlacementRegion placementRegion = placementCloud.regionList.get(index.regionIdx);
        PlacementAZ placementAZ = placementRegion.azList.get(index.azIdx);
        if (isEditUniverse) {
          decommissionNodeInAZ(nodes, placementAZ.uuid);
        } else {
          removeNodeInAZ(nodes, placementAZ.uuid);
        }
        if (placementAZ.numNodesInAZ > 0) {
          placementAZ.numNodesInAZ--;
        }
      }
      iter++;
    }
    nodes.addAll(deltaNodesSet);
  }

  private static void configureNodesUsingUserIntent(Cluster cluster,
                                                    Collection<NodeDetails> nodeDetailsSet,
                                                    String nodePrefix,
                                                    boolean isEditUniverse) {
    UserIntent userIntent = cluster.userIntent;
    Set<NodeDetails> nodesInCluster = nodeDetailsSet.stream()
            .filter(n -> n.placementUuid.equals(cluster.uuid))
            .collect(Collectors.toSet());
    int numDeltaNodes = userIntent.numNodes - nodesInCluster.size();
    Map<String, NodeDetails> deltaNodesMap = new HashMap<String, NodeDetails>();
    Map<UUID, Integer> azUuidToNumNodes = getAzUuidToNumNodes(nodeDetailsSet);
    LOG.info("Nodes desired={} vs existing={}.", userIntent.numNodes, nodesInCluster.size());
    if (numDeltaNodes < 0) {
      // Desired action is to remove nodes from a given cluster.
      Iterator<NodeDetails> nodeIter = nodeDetailsSet.iterator();
      int deleteCounter = 0;
      while (nodeIter.hasNext()) {
        NodeDetails currentNode = nodeIter.next();
        if (currentNode.isMaster || !currentNode.placementUuid.equals(cluster.uuid)) {
          continue;
        }
        if (isEditUniverse) {
          if (currentNode.isActive()) {
            currentNode.state = NodeDetails.NodeState.ToBeRemoved;
            LOG.debug("Removing node [{}].", currentNode);
            deleteCounter++;
          }
        } else {
          nodeIter.remove();
          deleteCounter++;
        }
        if (deleteCounter == -numDeltaNodes) {
          break;
        }
      }
    } else {
      // Desired action is to add nodes.
      LinkedHashSet<PlacementIndexes> indexes =
              findPlacementsOfAZUuid(sortByValues(azUuidToNumNodes, true), cluster);
      int startIndex = getNextIndexToConfigure(nodeDetailsSet);
      addNodeDetailSetToTaskParams(indexes, startIndex, numDeltaNodes, cluster, nodeDetailsSet,
                                   nodePrefix, deltaNodesMap);
    }
  }

  private static void configureDefaultNodeStates(Cluster cluster,
                                                 Collection<NodeDetails> nodeDetailsSet,
                                                 String nodePrefix,
                                                 Universe universe) {
    UserIntent userIntent = cluster.userIntent;
    int startIndex = universe != null ? getNextIndexToConfigure(universe.getNodes()) :
        getNextIndexToConfigure(nodeDetailsSet);
    int numNodes = userIntent.numNodes;
    int numMastersToChoose =  userIntent.replicationFactor;
    Map<String, NodeDetails> deltaNodesMap = new HashMap<>();
    LinkedHashSet<PlacementIndexes> indexes = getBasePlacement(numNodes, cluster);
    addNodeDetailSetToTaskParams(indexes, startIndex, numNodes, cluster, nodeDetailsSet,
                                 nodePrefix, deltaNodesMap);

    // Full move.
    if (universe != null) {
      Collection<NodeDetails> existingNodes = universe.getNodesInCluster(cluster.uuid);
      LOG.info("Decommissioning {} nodes.", existingNodes.size());
      for (NodeDetails node : existingNodes) {
        node.state = NodeDetails.NodeState.ToBeRemoved;
        nodeDetailsSet.add(node);
      }

      // Select the masters for this cluster based on subnets.
      if (cluster.clusterType.equals(ClusterType.PRIMARY)) {
        selectMasters(deltaNodesMap, numMastersToChoose);
      }
    }
  }

  /**
   * Configures the state of the nodes that need to be created or the ones to be removed.
   *
   * @param taskParams the taskParams for the Universe to be configured.
   * @param universe   the current universe if it exists (only when called during edit universe).
   * @param mode       mode in which to configure with user specified AZ's or round-robin (default).
   *
   * @return set of node details with their placement info filled in.
   */
  private static void configureNodeStates(UniverseDefinitionTaskParams taskParams,
                                          Universe universe,
                                          PlacementInfoUtil.ConfigureNodesMode mode,
                                          Cluster cluster) {
    switch (mode) {
      case NEW_CONFIG:
        // This case covers create universe and full move edit.
        configureDefaultNodeStates(cluster, taskParams.nodeDetailsSet, taskParams.nodePrefix,
                                   universe);
        break;
      case UPDATE_CONFIG_FROM_PLACEMENT_INFO:
        // The case where there are custom expand/shrink in the placement info.
        configureNodesUsingPlacementInfo(cluster, taskParams.nodeDetailsSet, taskParams.nodePrefix,
                                         universe != null);
        break;
      case UPDATE_CONFIG_FROM_USER_INTENT:
        // Case where userIntent numNodes has to be favored - as it is different from the
        // sum of all per AZ node counts).
        configureNodesUsingUserIntent(cluster, taskParams.nodeDetailsSet, taskParams.nodePrefix,
                                      universe != null);
        updatePlacementInfo(taskParams.getNodesInCluster(cluster.uuid), cluster.placementInfo);
        break;
      case NEW_CONFIG_FROM_PLACEMENT_INFO:
        configureNodeEditUsingPlacementInfo(taskParams);
    }

    // Choose new Masters if this is the Primary cluster and we need more Masters.
    if (cluster.clusterType.equals(ClusterType.PRIMARY)) {
      Set<NodeDetails> primaryNodes = taskParams.getNodesInCluster(cluster.uuid);
      int numMastersToChoose = cluster.userIntent.replicationFactor - getNumMasters(primaryNodes);
      if (numMastersToChoose > 0 && universe != null) {
        LOG.info("Selecting {} masters.", numMastersToChoose);
        selectMasters(primaryNodes, numMastersToChoose);
      }
    }
  }

  /**
   * Find a node which has tservers only to decommission, from the given AZ.
   * Node should be an active T-Server and should not be Master.
   * @param nodes   the list of nodes from which to choose the victim.
   * @param targetAZUuid AZ in which the node should be present.
   */
  private static void decommissionNodeInAZ(Collection<NodeDetails> nodes,
                                           UUID targetAZUuid) {
    NodeDetails nodeDetails = findActiveTServerOnlyInAz(nodes, targetAZUuid);
    if (nodeDetails == null) {
      LOG.error("Could not find an active node running tservers only in AZ {}. All nodes: {}.",
                targetAZUuid, nodes);
      throw new IllegalStateException("Should find an active running tserver.");
    } else {
      nodeDetails.state = NodeDetails.NodeState.ToBeRemoved;
      LOG.debug("Removing node [{}].", nodeDetails);
    }
  }

  /**
   * Method takes a placementIndex and returns a NodeDetail object for it in order to add a node.
   *
   * @param cluster     The current cluster.
   * @param nodePrefix  The prefix for the name of the node.
   * @param index       The placement index combination.
   * @param nodeIdx     Node index to be used in node name.
   * @return a NodeDetails object.
   */
  private static NodeDetails createNodeDetailsWithPlacementIndex(Cluster cluster,
                                                                 String nodePrefix,
                                                                 PlacementIndexes index,
                                                                 int nodeIdx) {
    NodeDetails nodeDetails = new NodeDetails();
    // Create a temporary node name. These are fixed once the operation is actually run.
    nodeDetails.nodeName = nodePrefix + "-fake-n" + nodeIdx;
    // Set the cluster.
    nodeDetails.placementUuid = cluster.uuid;
    // Set the cloud.
    PlacementCloud placementCloud = cluster.placementInfo.cloudList.get(index.cloudIdx);
    nodeDetails.cloudInfo = new CloudSpecificInfo();
    nodeDetails.cloudInfo.cloud = placementCloud.code;
    // Set the region.
    PlacementRegion placementRegion = placementCloud.regionList.get(index.regionIdx);
    nodeDetails.cloudInfo.region = placementRegion.code;
    // Set the AZ and the subnet.
    PlacementAZ placementAZ = placementRegion.azList.get(index.azIdx);
    nodeDetails.azUuid = placementAZ.uuid;
    nodeDetails.cloudInfo.az = placementAZ.name;
    nodeDetails.cloudInfo.subnet_id = placementAZ.subnet;
    nodeDetails.cloudInfo.instance_type = cluster.userIntent.instanceType;
    nodeDetails.cloudInfo.spotPrice = cluster.userIntent.spotPrice;
    nodeDetails.cloudInfo.assignPublicIP = cluster.userIntent.assignPublicIP;
    nodeDetails.cloudInfo.useTimeSync = cluster.userIntent.useTimeSync;
    // Set the tablet server role to true.
    nodeDetails.isTserver = true;
    // Set the node id.
    nodeDetails.nodeIdx = nodeIdx;
    // We are ready to add this node.
    nodeDetails.state = NodeDetails.NodeState.ToBeAdded;
    LOG.debug("Placed new node [{}] at cloud:{}, region:{}, az:{}. uuid {}.",
            nodeDetails, index.cloudIdx, index.regionIdx, index.azIdx, nodeDetails.azUuid);
    return nodeDetails;
  }

  /**
   * Construct a delta node set and add all those nodes to the Universe's set of nodes.
   */
  private static void addNodeDetailSetToTaskParams(LinkedHashSet<PlacementIndexes> indexes,
                                                   int startIndex,
                                                   int numDeltaNodes,
                                                   Cluster cluster,
                                                   Collection<NodeDetails> nodeDetailsSet,
                                                   String nodePrefix,
                                                   Map<String, NodeDetails> deltaNodesMap) {
    Set<NodeDetails> deltaNodesSet = new HashSet<NodeDetails>();
    // Create the names and known properties of all the nodes to be created.
    Iterator<PlacementIndexes> iter = indexes.iterator();
    for (int nodeIdx = startIndex; nodeIdx < startIndex + numDeltaNodes; nodeIdx++) {
      PlacementIndexes index = null;
      if (iter.hasNext()) {
        index = iter.next();
      } else {
        iter = indexes.iterator();
        index = iter.next();
      }
      NodeDetails nodeDetails = createNodeDetailsWithPlacementIndex(cluster, nodePrefix, index, nodeIdx);
      deltaNodesSet.add(nodeDetails);
      deltaNodesMap.put(nodeDetails.nodeName, nodeDetails);
    }
    nodeDetailsSet.addAll(deltaNodesSet);
  }

  public static void selectMasters(Collection<NodeDetails> nodes, int numMastersToChoose) {
    Map<String, NodeDetails> deltaNodesMap = new HashMap<String, NodeDetails>();
    for (NodeDetails node : nodes) {
      deltaNodesMap.put(node.nodeName, node);
    }
    selectMasters(deltaNodesMap, numMastersToChoose);
  }

  /**
   * Given a set of nodes and the number of masters, selects the masters and marks them as such.
   *
   * @param nodesMap   : a map of node name to NodeDetails
   * @param numMasters : the number of masters to choose
   * @return nothing
   */
  private static void selectMasters(Map<String, NodeDetails> nodesMap, int numMasters) {
    // Group the cluster nodes by subnets.
    Map<String, TreeSet<String>> subnetsToNodenameMap = new HashMap<String, TreeSet<String>>();
    for (Entry<String, NodeDetails> entry : nodesMap.entrySet()) {
      String subnet = entry.getValue().cloudInfo.subnet_id;
      if (!subnetsToNodenameMap.containsKey(subnet)) {
        subnetsToNodenameMap.put(subnet, new TreeSet<String>());
      }
      TreeSet<String> nodeSet = subnetsToNodenameMap.get(subnet);
      // Add the node name into the node set.
      nodeSet.add(entry.getKey());
    }
    LOG.info("Subnet map has {}, nodesMap has {}, need {} masters.",
            subnetsToNodenameMap.size(), nodesMap.size(), numMasters);
    // Choose the masters such that we have one master per subnet if there are enough subnets.
    int numMastersChosen = 0;
    if (subnetsToNodenameMap.size() >= maxMasterSubnets) {
      while (numMastersChosen < numMasters) {
        // Get one node from each subnet and removes it so that a different node is picked in next.
        for (Entry<String, TreeSet<String>> entry : subnetsToNodenameMap.entrySet()) {
          TreeSet<String> value = entry.getValue();
          if (value.isEmpty()) {
            continue;
          }
          String nodeName = value.first();
          value.remove(nodeName);
          subnetsToNodenameMap.put(entry.getKey(), value);
          NodeDetails node = nodesMap.get(nodeName);
          node.isMaster = true;
          LOG.info("Chose node '{}' as a master from subnet {}.", nodeName, entry.getKey());
          numMastersChosen++;
          if (numMastersChosen == numMasters) {
            break;
          }
        }
      }
    } else {
      // We do not have enough subnets. Simply pick enough masters.
      for (NodeDetails node : nodesMap.values()) {
        if (node.isMaster) {
          continue;
        }
        node.isMaster = true;
        LOG.info("Chose node {} as a master from subnet {}.",
                node.nodeName, node.cloudInfo.subnet_id);
        numMastersChosen++;
        if (numMastersChosen == numMasters) {
          break;
        }
      }
      if (numMastersChosen < numMasters) {
        throw new IllegalStateException("Could not pick " + numMasters + " masters, got only " +
                numMastersChosen + ". Nodes info. " + nodesMap);
      }
    }
  }

  // Returns the start index for provisioning new nodes based on the current maximum node index
  // across existing nodes. If called for a new universe being created, then it will return a
  // start index of 1.
  public static int getStartIndex(Collection<NodeDetails> nodes) {
    int maxNodeIdx = 0;
    for (NodeDetails node : nodes) {
      if (node.state != NodeDetails.NodeState.ToBeAdded && node.nodeIdx > maxNodeIdx) {
        maxNodeIdx = node.nodeIdx;
      }
    }
    return maxNodeIdx + 1;
  }

  // Returns the start index for provisioning new nodes based on the current maximum node index.
  public static int getNextIndexToConfigure(Collection<NodeDetails> existingNodes) {
    int maxNodeIdx = 0;
    if (existingNodes != null) {
      for (NodeDetails node : existingNodes) {
        if (node.nodeIdx > maxNodeIdx) {
          maxNodeIdx = node.nodeIdx;
        }
      }
    }
    return maxNodeIdx + 1;
  }

  public static boolean isRegionListMultiAZ(UserIntent userIntent) {
    List<UUID> regionList = userIntent.regionList;
    if (regionList.size() > 1) {
      return true;
    }
    if (Region.get(regionList.get(0)).zones != null &&
        Region.get(regionList.get(0)).zones.size() > 1) {
      return true;
    }
    return false;
  }

  public static PlacementInfo getPlacementInfo(UserIntent userIntent) {
    if (userIntent == null || userIntent.regionList == null || userIntent.regionList.isEmpty()) {
      LOG.info("No placement due to userIntent={} or regions={}.", userIntent, userIntent.regionList);
      return null;
    }
    verifyNodesAndRF(userIntent.numNodes, userIntent.replicationFactor);

    // Make sure the preferred region is in the list of user specified regions.
    if (userIntent.preferredRegion != null &&
        !userIntent.regionList.contains(userIntent.preferredRegion)) {
      throw new RuntimeException("Preferred region " + userIntent.preferredRegion +
          " not in user region list.");
    }
    // Create the placement info object.
    PlacementInfo placementInfo = new PlacementInfo();
    boolean useSingleAZ = !isRegionListMultiAZ(userIntent);
    // Handle the single AZ deployment case or RF=1 case.
    if (useSingleAZ) {
      // Select an AZ in the required region.
      List<AvailabilityZone> azList =
          AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0));
      if (azList.isEmpty()) {
        throw new RuntimeException("No AZ found for region: " + userIntent.regionList.get(0));
      }
      Collections.shuffle(azList);
      UUID azUUID = azList.get(0).uuid;
      LOG.info("Using AZ {} out of {}", azUUID, azList.size());
      // Add all replicas into the same AZ.
      for (int idx = 0; idx < userIntent.replicationFactor; idx++) {
        addPlacementZone(azUUID, placementInfo);
      }
      return placementInfo;
    } else {
      List<AvailabilityZone> totalAzsInRegions = new ArrayList<>();
      for (int idx = 0; idx < userIntent.regionList.size(); idx++) {
        totalAzsInRegions.addAll(AvailabilityZone.getAZsForRegion(userIntent.regionList.get(idx)));
      }
      if (totalAzsInRegions.size() <= 2) {
        for (int idx = 0; idx < userIntent.numNodes; idx++) {
          addPlacementZone(totalAzsInRegions.get(idx % totalAzsInRegions.size()).uuid,
                           placementInfo);
        }
      } else {
        // If one region is specified, pick all three AZs from it. Make sure there are enough regions.
        if (userIntent.regionList.size() == 1) {
          selectAndAddPlacementZones(userIntent.regionList.get(0), placementInfo, 3);
        } else if (userIntent.regionList.size() == 2) {
          // Pick two AZs from one of the regions (preferred region if specified).
          UUID preferredRegionUUID = userIntent.preferredRegion;
          // If preferred region was not specified, then pick the region that has at least 2 zones as
          // the preferred region.
          if (preferredRegionUUID == null) {
            if (AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0)).size() >= 2) {
              preferredRegionUUID = userIntent.regionList.get(0);
            } else {
              preferredRegionUUID = userIntent.regionList.get(1);
            }
          }
          selectAndAddPlacementZones(preferredRegionUUID, placementInfo, 2);

          // Pick one AZ from the other region.
          UUID otherRegionUUID = userIntent.regionList.get(0).equals(preferredRegionUUID) ?
                  userIntent.regionList.get(1) :
                  userIntent.regionList.get(0);
          selectAndAddPlacementZones(otherRegionUUID, placementInfo, 1);
        } else if (userIntent.regionList.size() == 3) {
          // If the user has specified three regions, pick one AZ from each region.
          for (int idx = 0; idx < 3; idx++) {
            selectAndAddPlacementZones(userIntent.regionList.get(idx), placementInfo, 1);
          }
        } else {
          throw new RuntimeException("Unsupported placement, num regions " +
                  userIntent.regionList.size() + " is more than replication factor of " +
                  userIntent.replicationFactor);
        }
      }
    }
    return placementInfo;
  }

  private static void selectAndAddPlacementZones(UUID regionUUID,
                                                 PlacementInfo placementInfo,
                                                 int numZones) {
    // Find the region object.
    Region region = Region.get(regionUUID);
    LOG.debug("Selecting and adding " + numZones + " zones in region " + region.name);
    // Find the AZs for the required region.
    List<AvailabilityZone> azList = AvailabilityZone.getAZsForRegion(regionUUID);
    if (azList.size() < numZones) {
      throw new RuntimeException("Need at least " + numZones + " zones but found only " +
              azList.size() + " for region " + region.name);
    }
    Collections.shuffle(azList);
    // Pick as many AZs as required.
    for (int idx = 0; idx < numZones; idx++) {
      addPlacementZone(azList.get(idx).uuid, placementInfo);
    }
  }

  public static int getNumMasters(Set<NodeDetails> nodes) {
    int count = 0;
    for (NodeDetails node : nodes) {
      if (node.isMaster) {
        count++;
      }
    }
    return count;
  }

  private static void addPlacementZone(UUID zone, PlacementInfo placementInfo) {
    // Get the zone, region and cloud.
    AvailabilityZone az = AvailabilityZone.find.byId(zone);
    Region region = az.region;
    Provider cloud = region.provider;
    LOG.debug("provider: {}", cloud);
    // Find the placement cloud if it already exists, or create a new one if one does not exist.
    PlacementCloud placementCloud = null;
    for (PlacementCloud pCloud : placementInfo.cloudList) {
      if (pCloud.uuid.equals(cloud.uuid)) {
        placementCloud = pCloud;
        break;
      }
    }
    if (placementCloud == null) {
      placementCloud = new PlacementCloud();
      placementCloud.uuid = cloud.uuid;
      placementCloud.code = cloud.code;
      placementInfo.cloudList.add(placementCloud);
    }

    // Find the placement region if it already exists, or create a new one.
    PlacementRegion placementRegion = null;
    for (PlacementRegion pRegion : placementCloud.regionList) {
      if (pRegion.uuid.equals(region.uuid)) {
        placementRegion = pRegion;
        break;
      }
    }
    if (placementRegion == null) {
      placementRegion = new PlacementRegion();
      placementRegion.uuid = region.uuid;
      placementRegion.code = region.code;
      placementRegion.name = region.name;
      placementCloud.regionList.add(placementRegion);
    }

    // Find the placement AZ in the region if it already exists, or create a new one.
    PlacementAZ placementAZ = null;
    for (PlacementAZ pAz : placementRegion.azList) {
      if (pAz.uuid.equals(az.uuid)) {
        placementAZ = pAz;
        break;
      }
    }
    if (placementAZ == null) {
      placementAZ = new PlacementAZ();
      placementAZ.uuid = az.uuid;
      placementAZ.name = az.name;
      placementAZ.replicationFactor = 0;
      placementAZ.subnet = az.subnet;
      placementAZ.isAffinitized = true;
      placementRegion.azList.add(placementAZ);
    }
    placementAZ.replicationFactor++;
    placementAZ.numNodesInAZ++;
  }

  // Removes a given node from universe nodeDetailsSet
  public static void removeNodeByName(String nodeName, Set<NodeDetails> nodeDetailsSet) {
    Iterator<NodeDetails> nodeIter = nodeDetailsSet.iterator();
    while (nodeIter.hasNext()) {
      NodeDetails currentNode = nodeIter.next();
      if (currentNode.nodeName.equals(nodeName)) {
        nodeIter.remove();
        return;
      }
    }
  }

  // Checks the status of the node by given name in current universe
  public static boolean isNodeRemovable(String nodeName, Set<NodeDetails> nodeDetailsSet) {
    Iterator<NodeDetails> nodeIter = nodeDetailsSet.iterator();
    while (nodeIter.hasNext()) {
      NodeDetails currentNode = nodeIter.next();
      if (currentNode.nodeName.equals(nodeName) && currentNode.isRemovable()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Given a node, return its tserver & master alive/not-alive status and detect if the node isn't
   * running.
   *
   * @param nodeDetails The node to get the status of.
   * @param nodeJson Metadata about all the nodes in the node's universe.
   * @return JsonNode with the following format:
   *  {
   *    tserver_alive: true/false,
   *    master_alive: true/false,
   *    node_status: <NodeDetails.NodeState>
   *  }
   */
  private static ObjectNode getNodeAliveStatus(NodeDetails nodeDetails, JsonNode nodeJson) {
    boolean nodeAlive = false;
    boolean tserverAlive = false;
    boolean masterAlive = false;

    if (nodeJson.get("data") != null) {
      for (JsonNode json : nodeJson.get("data")) {
        String[] name = json.get("name").asText().split(":", 2);
        if (name.length == 2 && name[0].equals(nodeDetails.cloudInfo.private_ip)) {
          switch (SwamperHelper.TargetType.createFromPort(Integer.valueOf(name[1]))) {
            case NODE_EXPORT:
              for (JsonNode upData : json.get("y")) {
                nodeAlive = nodeAlive || upData.asText().equals("1");
              }
              if (!nodeAlive && nodeDetails.isQueryable()) {
                nodeDetails.state = NodeDetails.NodeState.Unreachable;
              }
              break;
            case TSERVER_EXPORT:
              for (JsonNode upData : json.get("y")) {
                tserverAlive = tserverAlive || upData.asText().equals("1");
              }
              break;
            case MASTER_EXPORT:
              for (JsonNode upData : json.get("y")) {
                masterAlive = masterAlive || upData.asText().equals("1");
              }
              break;
            case CQL_EXPORT: // Ignore results from the CQL port
            case REDIS_EXPORT: // Ignore results from the Redis port
              break;
            default:
              if (Integer.valueOf(name[1]) != 0) {
                LOG.error("Invalid port " + name[1]);
              }
              break;
          }
        }
      }
    }

    return Json.newObject()
            .put("tserver_alive", tserverAlive)
            .put("master_alive", masterAlive)
            .put("node_status", nodeDetails.state.toString());
  }

  /**
   * Helper function to get the status for each node and the alive/not alive status for each master
   * and tserver.
   *
   * @param universe The universe to process alive status for.
   * @param metricQueryResult The result of the query for node, master, and tserver status of the
   *                          universe.
   * @return The response object containing the status of each node in the universe.
   */
  private static ObjectNode constructUniverseAliveStatus(Universe universe,
                                                         JsonNode metricQueryResult) {
    ObjectNode response = Json.newObject();

    // If error detected, update state and exit. Otherwise, get and return per-node liveness.
    if (metricQueryResult.has("error")) {
      for (NodeDetails nodeDetails : universe.getNodes()) {
        nodeDetails.state = NodeDetails.NodeState.Unreachable;
        ObjectNode result = Json.newObject()
                .put("tserver_alive", false)
                .put("master_alive", false)
                .put("node_status", nodeDetails.state.toString());
        response.put(nodeDetails.nodeName, result);
      }
    } else {
      JsonNode nodeJson = metricQueryResult.get(UNIVERSE_ALIVE_METRIC);
      for (NodeDetails nodeDetails : universe.getNodes()) {
        ObjectNode result = getNodeAliveStatus(nodeDetails, nodeJson);
        response.put(nodeDetails.nodeName, result);
      }
    }
    return response;
  }

  /**
   * Given a universe, return a status for each master and tserver as alive/not alive and the
   * node's status.
   *
   * @param universe The universe to query alive status for.
   * @param metricQueryHelper Helper to execute the metrics query.
   * @return JsonNode with the following format:
   *  {
   *    universe_uuid: <universeUUID>,
   *    <node_name_n>: {tserver_alive: true/false, master_alive: true/false,
   *                    node_status: <NodeDetails.NodeState>}
   *  }
   */
  public static JsonNode getUniverseAliveStatus(Universe universe,
                                                MetricQueryHelper metricQueryHelper) {
    List<String> metricKeys = ImmutableList.of(UNIVERSE_ALIVE_METRIC);

    // Set up params for metrics query.
    Map<String, String> params = new HashMap<>();
    DateTime now = DateTime.now();
    params.put("end", Long.toString(now.getMillis()/1000, 10));
    DateTime start = now.minusMinutes(1);
    params.put("start", Long.toString(start.getMillis()/1000, 10));
    ObjectNode filterJson = Json.newObject();
    filterJson.put("node_prefix", universe.getUniverseDetails().nodePrefix);
    params.put("filters", Json.stringify(filterJson));
    for (int i = 0; i < metricKeys.size(); ++i) {
      params.put("metrics[" + Integer.toString(i) + "]", metricKeys.get(i));
    }
    params.put("step", "30");

    // Execute query and check for errors.
    JsonNode metricQueryResult = metricQueryHelper.query(metricKeys, params);

    // Persist the desired node information into the DB.
    ObjectNode response = constructUniverseAliveStatus(universe, metricQueryResult);
    response.put("universe_uuid", universe.universeUUID.toString());

    return metricQueryResult.has("error") ? metricQueryResult : response;
  }
}
