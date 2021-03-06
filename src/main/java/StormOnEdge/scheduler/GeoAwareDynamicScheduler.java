package StormOnEdge.scheduler;

import StormOnEdge.grouping.topology.GlobalTaskGroup;
import StormOnEdge.grouping.topology.LocalTaskGroup;
import StormOnEdge.grouping.topology.TaskGroup;
import StormOnEdge.state.CloudState.CloudsInfo;
import StormOnEdge.state.CloudState.FileBasedCloudsInfo;
import StormOnEdge.state.SourceState.FileSourceInfo;
import StormOnEdge.state.SourceState.SourceInfo;
import StormOnEdge.state.ZGState.ZGConnector;
import StormOnEdge.state.ZGState.ZookeeperZGConnector;
import backtype.storm.generated.Bolt;
import backtype.storm.generated.GlobalStreamId;
import backtype.storm.generated.SpoutSpec;
import backtype.storm.generated.StormTopology;
import backtype.storm.scheduler.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mortbay.util.MultiMap;

import java.io.File;
import java.io.FileWriter;
import java.util.*;


@SuppressWarnings("Duplicates")
public class GeoAwareDynamicScheduler implements IScheduler {

  Random rand = new Random(System.currentTimeMillis());
  JSONParser parser = new JSONParser();
  CloudsInfo cloudInfo;
  SourceInfo sourceInformation;
  Map storm_config;

  final String ackerBolt = "__acker";
	final String CONF_sourceCloudKey = "geoAwareScheduler.in-SourceInfo";
  final String CONF_cloudLocatorKey = "geoAwareScheduler.in-CloudInfo";
  final String CONF_schedulerResult = "geoAwareScheduler.out-SchedulerResult";
	//final String CONF_ZoneGroupingInput = "geoScheduler.out-ZoneGrouping";

	//String taskGroupListFile = "/home/kend/fromSICSCloud/Scheduler-GroupList.txt";
  //String schedulerResultFile = "/home/kend/SchedulerResult.csv";
  //String pairSupervisorTaskFile = "/home/kend/fromSICSCloud/PairSupervisorTasks.txt";

  @SuppressWarnings("rawtypes")
  public void prepare(Map conf) {
    //Retrieve data from storm.yaml config file
    storm_config = conf;
  }

  @SuppressWarnings({"unchecked", "ConstantConditions"})
  public void schedule(Topologies topologies, Cluster cluster) {

    System.out.println("GeoAwareGroupScheduler: begin scheduling");

    List<SupervisorDetails> supervisors;
    HashMap<String, CloudAssignment> clouds;

    //
    System.out.println("Initializing the Spout sources information");
    try {
      // TODO: based on the configurations, should instantiate the right implementations, e.g., filebased, remote or etc.
      sourceInformation = new FileSourceInfo(CONF_sourceCloudKey, storm_config);

      if (sourceInformation.getSpoutNames().size() <= 0)
        throw new Exception("no spout with source information, StormOnEdge.scheduler stopped");

    } catch (Exception e) {
      System.out.println(e.getMessage());
      System.out.println("Exception when reading the source information file. Scheduler stopped");
      return;
    }
    System.out.println("Spout sources OK");

    //
    System.out.println("Initializing the cloud quality information");
    try {
      // TODO: based on the configurations, should instantiate the right implementations, e.g., filebased, remote or etc.
      cloudInfo = new FileBasedCloudsInfo(CONF_cloudLocatorKey, storm_config);
    } catch (Exception e) {
      System.out.println(e.getMessage());
      System.out.println("Exception when loading the Cloud quality. Scheduler stopped");
      return;
    }
    System.out.println("cloud quality information OK");


    System.out.println("Categorizing the supervisor based on cloud names");
    supervisors = new ArrayList<SupervisorDetails>(cluster.getSupervisors().values());
    clouds = new HashMap<String, CloudAssignment>();

    //map the supervisors and workers based on cloud names
    for (SupervisorDetails supervisor : supervisors) {
      Map<String, Object> metadata = (Map<String, Object>) supervisor.getSchedulerMeta();
      if (metadata.get("cloud-name") != null) {
        CloudAssignment c;

        if (!clouds.containsKey(metadata.get("cloud-name"))) {
          clouds.put(metadata.get("cloud-name").toString(), new CloudAssignment(metadata.get("cloud-name").toString()));
          System.out.println("[Cloud] Create new cloud called " + metadata.get("cloud-name").toString());
        }

        c = clouds.get(metadata.get("cloud-name").toString());
        c.addSupervisor(supervisor);
        if (!cluster.getAvailableSlots(supervisor).isEmpty()) {
          c.addWorkers(cluster.getAvailableSlots(supervisor));
        }
      }
    }

    //print the worker list
    for (CloudAssignment C : clouds.values()) {
      System.out.println(C.getName() + " :");
      System.out.println("Available Workers: " + C.getWorkers() + "\n");
    }


    //Start Scheduling
    for (TopologyDetails topology : topologies.getTopologies()) {

      System.out.println("Topology name : " + topology.getName());

      LinkedHashMap<String, LocalTaskGroup> localTaskList = new LinkedHashMap<String, LocalTaskGroup>();
      LinkedHashMap<String, GlobalTaskGroup> globalTaskList = new LinkedHashMap<String, GlobalTaskGroup>();

      if (!cluster.needsScheduling(topology) || cluster.getNeedsSchedulingComponentToExecutors(topology).isEmpty()) {
        System.out.println("This topology doesn't need scheduling.");
      } else {

        MultiMap executorWorkerMap = new MultiMap();
        MultiMap executorCloudMap = new MultiMap();

        StormTopology st = topology.getTopology();
        Map<String, Bolt> bolts = st.get_bolts();
        Map<String, SpoutSpec> spouts = st.get_spouts();

        System.out.println("LOG: Categorizing Spouts into TaskGroup");
        for (String name : spouts.keySet()) {

          SpoutSpec spoutSpec = spouts.get(name);

          try {
            JSONObject conf = (JSONObject) parser.parse(spoutSpec.get_common().get_json_conf());

            if (conf.get("group-name") != null) {
              String groupName = (String) conf.get("group-name");
              LocalTaskGroup schedulerGroup;

              //Spout only reside in LocalTask
              if (localTaskList.containsKey(groupName)) {
                schedulerGroup = localTaskList.get(groupName);
              } else {
                //Create new LocalTaskGroup
                LocalTaskGroup newTaskGroup = new LocalTaskGroup(groupName);
                localTaskList.put(groupName, newTaskGroup);
                schedulerGroup = newTaskGroup;
              }

              if (schedulerGroup != null) {
                schedulerGroup.spoutsWithCloudParallelizationInfo.put(name, spoutSpec.get_common().get_parallelism_hint());
                for (String cloudName : sourceInformation.getCloudLocations(name)) {
                  CloudAssignment c = clouds.get(cloudName);
                  schedulerGroup.taskGroupClouds.add(c);
                }

              } else {
                System.out.println("ERROR: " + name + " don't have any valid Taskgroup. This task will be ignored in scheduling");
              }
            }

          } catch (ParseException e) {
            e.printStackTrace();
          }
        }

        System.out.println("LOG: Categorizing Bolts into TaskGroup");
        for (String name : bolts.keySet()) {
          System.out.println(name);

          Bolt b = bolts.get(name);
          Set<GlobalStreamId> inputStreams = b.get_common().get_inputs().keySet();

          try {
            JSONObject conf = (JSONObject) parser.parse(b.get_common().get_json_conf());

            if (conf.get("group-name") != null) {
              String groupName = (String) conf.get("group-name");
              TaskGroup schedulerGroup;

							//each task only reside in one group
              //Check list of localTask first, then globalTask
              if (localTaskList.containsKey(groupName)) {
                schedulerGroup = localTaskList.get(groupName);
              } else if (globalTaskList.containsKey(groupName)) {
                schedulerGroup = globalTaskList.get(groupName);
              } else {
                //Create new GlobalTaskGroup
                GlobalTaskGroup newTaskGroup = new GlobalTaskGroup(groupName);
                globalTaskList.put(groupName, newTaskGroup);
                schedulerGroup = newTaskGroup;
              }

              if (schedulerGroup != null) {
                schedulerGroup.boltsWithCloudParallelizationInfo.put(name, b.get_common().get_parallelism_hint());

                for (GlobalStreamId streamId : inputStreams) {
                  System.out.println("--dependent to " + streamId.get_componentId());
                  schedulerGroup.boltDependencies.add(streamId.get_componentId());
                }
              } else {
                System.out.println("ERROR: " + name + " don't have any valid TaskGroup. This task will be ignored in scheduling");
              }
            }

          } catch (ParseException e) {
            e.printStackTrace();
          }
        }

        Map<String, List<ExecutorDetails>> componentToExecutors = cluster.getNeedsSchedulingComponentToExecutors(topology);
        String needScheduling = "needs scheduling(component->executor): " + componentToExecutors;
        System.out.println(needScheduling);

				//Local group task distribution
        //for each spouts:
        //get clouds 
        for (LocalTaskGroup localGroup : localTaskList.values()) {
          try {
            System.out.println("LOG: " + localGroup.name + "distribution");

            for (Map.Entry<String, Integer> spout : localGroup.spoutsWithCloudParallelizationInfo.entrySet()) {
              String spoutName = spout.getKey();
              int spoutParHint = spout.getValue();
              System.out.println("-" + spoutName + ": " + spoutParHint);

              List<ExecutorDetails> executors = componentToExecutors.get(spoutName);
              localTaskGroupDeployment(localGroup, executors, spout, executorWorkerMap, executorCloudMap);
            }

            for (Map.Entry<String, Integer> bolt : localGroup.boltsWithCloudParallelizationInfo.entrySet()) {
              String boltName = bolt.getKey();
              int parHint = bolt.getValue();
              System.out.println("-" + boltName + ": " + parHint);

              List<ExecutorDetails> executors = componentToExecutors.get(boltName);
              localTaskGroupDeployment(localGroup, executors, bolt, executorWorkerMap, executorCloudMap);
            }

          } catch (Exception e) {
            System.out.println(e);
          }
        }

        //Global group task distribution
        for (TaskGroup globalTask : globalTaskList.values()) {
          try {
            System.out.println("LOG: " + globalTask.name + " distribution");

            Set<String> cloudDependencies = new HashSet<String>();
            for (String dependentExecutors : globalTask.boltDependencies) {
              if (executorCloudMap.getValues(dependentExecutors) != null)
                cloudDependencies.addAll((List<String>) executorCloudMap.getValues(dependentExecutors));
            }

            //Set<String> cloudSet = supervisorsByCloudName.keySet();
            Set<String> cloudSet = clouds.keySet();
            System.out.println("-cloudDependencies: " + cloudDependencies);
            String chosenCloud = cloudInfo.bestCloud(FileBasedCloudsInfo.Type.MinMax, cloudSet, cloudDependencies);
						//String chosenCloud = "CloudMidA"; //hardcoded

            if (chosenCloud == null) {
              System.out.println("WARNING! no cloud chosen for this group!");
            } else {
              System.out.println("-chosenCloud: " + chosenCloud);
            }

            CloudAssignment c;

            if (!clouds.containsKey(chosenCloud)) {
              System.out.println("WARNING! " + chosenCloud + " is not available!");
            } else {
              c = clouds.get(chosenCloud);
              globalTask.taskGroupClouds.add(c);

              for (String bolt : globalTask.boltsWithCloudParallelizationInfo.keySet()) {
                System.out.println("---" + bolt);
                List<ExecutorDetails> executors = new ArrayList<ExecutorDetails>();
                executors.addAll(componentToExecutors.get(bolt));

                //get only one cloud in this GlobalTask
                List<WorkerSlot> workersInCloud = c.getSelectedWorkers();

                System.out.println("-----subexecutors:" + executors);
                System.out.println("-----workers:" + workersInCloud);

                if (executors.size() == 0) {
                  System.out.println(globalTask.name + ": " + bolt + ": No executors");
                } else if (workersInCloud.isEmpty()) {
                  System.out.println(globalTask.name + ": " + chosenCloud + ": No workers");
                } else {
                  deployExecutorToWorkers(workersInCloud, executors, executorWorkerMap);
                  executorCloudMap.add(bolt, chosenCloud);

                  for (ExecutorDetails ex : executors) {
                    c.addTask(ex.getStartTask());
                  }
                }
              }

							//Addition for ackers
              //put all of the ack-bolts to the GlobalTask CloudAssignment
              if (!componentToExecutors.get(ackerBolt).isEmpty()) {
                List<ExecutorDetails> ackers = new ArrayList<ExecutorDetails>();
                ackers.addAll(componentToExecutors.get(ackerBolt));
                List<WorkerSlot> workerAckers = c.getSelectedWorkers();

                if (!ackers.isEmpty()) {
                  deployExecutorToWorkers(workerAckers, ackers, executorWorkerMap);

                  for (ExecutorDetails ex : ackers) {
                    c.addTask(ex.getStartTask());
                  }
                }
              }
            }

          } catch (Exception e) {
            System.out.println("Acker exception: \n" + e.getMessage());
          }
        }

        //Assign the tasks into cluster
        StringBuilder schedulerResultStringBuilder = new StringBuilder();
        schedulerResultStringBuilder.append("-----------------------------------\n");
        schedulerResultStringBuilder.append("Task number to worker location\n");
        schedulerResultStringBuilder.append("-----------------------------------\n\n");
        schedulerResultStringBuilder.append(needScheduling + "\n");
        //noinspection Duplicates
        for (Object ws : executorWorkerMap.keySet()) {
          List<ExecutorDetails> edetails = (List<ExecutorDetails>) executorWorkerMap.getValues(ws);
          WorkerSlot wslot = (WorkerSlot) ws;

          cluster.assign(wslot, topology.getId(), edetails);
          System.out.println("We assigned executors:" + executorWorkerMap.getValues(ws) + " to slot: [" + wslot.getNodeId() + ", " + wslot.getPort() + "]");
          schedulerResultStringBuilder.append(executorWorkerMap.getValues(ws) + " to slot: [" + wslot.getNodeId() + ", " + wslot.getPort() + "]\n");
        }

        //Printing the TaskGroup and their cloud location
        schedulerResultStringBuilder.append("\n\n-----------------------------------\n");
        schedulerResultStringBuilder.append("TaskGroup and Cloud locations\n");
        schedulerResultStringBuilder.append("-----------------------------------\n\n");

        for (TaskGroup Group : localTaskList.values()) {
          schedulerResultStringBuilder.append(Group.name + ": " + Group.taskGroupClouds + "\n");
        }
        for (TaskGroup Group : globalTaskList.values()) {
          schedulerResultStringBuilder.append(Group.name + ": " + Group.taskGroupClouds + "\n");
        }

        try {
          String resultPath = storm_config.get(CONF_schedulerResult).toString();
          if (resultPath != null || checkFileAvailability(resultPath)) {
            FileWriter writer = new FileWriter(resultPath, true);
            writer.write(schedulerResultStringBuilder.toString());
            writer.close();
          }
        } catch (Exception e) {
          System.out.println(e.getMessage());
        }

        /*
				* Create a file pair of CloudName and tasks assigned to this cloud
        * This file is needed for zoneGrouping
        * Connector can be modified by any means: Zookeeper, oracle, etc
        */
        // TODO: Based on the configuration decide what type of ZGConnector to use.
        //ZGConnector zgConnector = new FileBasedZGConnector(storm_config);
        ZGConnector zgConnector = new ZookeeperZGConnector(topology.getId());
        zgConnector.addInfo(clouds);
        zgConnector.writeInfo();
      }
    }

	        // let system's even StormOnEdge.scheduler handle the rest scheduling work
    // you can also use your own other StormOnEdge.scheduler here, this is what
    // makes storm's StormOnEdge.scheduler composable.
    new EvenScheduler().schedule(topologies, cluster);
  }

  private boolean checkFileAvailability(String path) {
    File file = new File(path);
    return file.exists();
  }

  private void localTaskGroupDeployment(LocalTaskGroup localGroup,
    List<ExecutorDetails> executors, Map.Entry<String, Integer> executorParallelism,
    MultiMap executorWorkerMap, MultiMap executorCloudMap) {
    String executorName = executorParallelism.getKey();
    int executorParHint = executorParallelism.getValue();

    if (executors == null || executors.isEmpty()) {
      System.out.println(localGroup.name + ": " + executorName + ": No executors");
    } else {
      int cloudIndex = 0;
      int executorPerCloud = executorParHint / localGroup.taskGroupClouds.size(); //for now, only work on even number between executors to workers
      for (CloudAssignment c : localGroup.taskGroupClouds) {
        int startidx = cloudIndex * executorPerCloud;
        int endidx = startidx + executorPerCloud;

        if (endidx > executors.size()) {
          endidx = executors.size() - 1;
        }

        List<ExecutorDetails> subExecutors = executors.subList(startidx, endidx);
        List<WorkerSlot> workers = c.getSelectedWorkers();

        System.out.println("---" + c.getName() + "\n" + "-----subExecutors:" + subExecutors);

        if (workers == null || workers.isEmpty()) {
          System.out.println(localGroup.name + ": " + c.getName() + ": No workers");
        } else {
          deployExecutorToWorkers(workers, subExecutors, executorWorkerMap);
          executorCloudMap.add(executorName, c.getName());

          for (ExecutorDetails ex : subExecutors) {
            c.addTask(ex.getStartTask());
          }
        }

        cloudIndex++;
      }
    }
  }

  private void deployExecutorToWorkers(List<WorkerSlot> cloudWorkers, List<ExecutorDetails> executors, MultiMap executorWorkerMap) {
    Iterator<WorkerSlot> workerIterator = cloudWorkers.iterator();
    Iterator<ExecutorDetails> executorIterator = executors.iterator();

    //if executors >= workers, do simple round robin
    //for all executors A to all supervisors B
    if (executors.size() >= cloudWorkers.size()) {
      while (executorIterator.hasNext() && workerIterator.hasNext()) {
        WorkerSlot w = workerIterator.next();
        ExecutorDetails ed = executorIterator.next();
        executorWorkerMap.add(w, ed);

        //Reset to first worker again
        if (!workerIterator.hasNext()) {
          workerIterator = cloudWorkers.iterator();
        }
      }
    } //if workers > executors, choose randomly
    //for all executors A to all supervisors B
    else {
      while (executorIterator.hasNext() && !cloudWorkers.isEmpty()) {
        WorkerSlot w = cloudWorkers.get(rand.nextInt(cloudWorkers.size()));
        ExecutorDetails ed = executorIterator.next();
        executorWorkerMap.add(w, ed);
      }
    }
  }

}
