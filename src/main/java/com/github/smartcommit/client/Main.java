package com.github.smartcommit.client;

import com.github.smartcommit.model.Group;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;

import java.io.File;
import java.util.Map;

public class Main {
  public static void main(String[] args) {
    // use basic configuration when packaging
    BasicConfigurator.configure();
    org.apache.log4j.Logger.getRootLogger().setLevel(Level.INFO);

    final String REPO_NAME = "fastjson";
    final String REPO_ID = String.valueOf(REPO_NAME.hashCode());
    final String REPO_PATH = Config.REPO_BASE_PATH + File.separator + REPO_NAME;
    final String TEMP_DIR = Config.TEMP_BASE_DIR + File.separator + REPO_NAME;
    String COMMIT_ID = "7f8251c76b347fe8a88f6a02445bb7cf569b799e";
    try {
//      SmartCommit smartCommit =
//          new SmartCommit(Config.REPO_ID, Config.REPO_NAME, Config.REPO_PATH, Config.TEMP_DIR);
      SmartCommit smartCommit =
          new SmartCommit(REPO_ID, REPO_NAME, REPO_PATH, TEMP_DIR);
      smartCommit.setDetectRefactorings(true);
      smartCommit.setProcessNonJavaChanges(false);
      smartCommit.setWeightThreshold(Config.WEIGHT_THRESHOLD);
      smartCommit.setMinSimilarity(Config.MIN_SIMILARITY);
      smartCommit.setMaxDistance(Config.MAX_DISTANCE);
      //      Map<String, Group> groups = smartCommit.analyzeWorkingTree();
      Map<String, Group> groups = smartCommit.analyzeCommit(COMMIT_ID);
      if (groups != null && !groups.isEmpty()) {
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
          Group group = entry.getValue();
          System.out.println(entry.getKey());
          System.out.println(group.toString());
        }

      } else {
        System.out.println("There is no changes.");
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
