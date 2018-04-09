//pipeline to run sonar tests for feature branch.

//pipeline to run junit tests. All jenkins job parameters are in hybris_module.groovy file

//Run on the custom node, env.NODE_NAME is a variable from Jenkins master node
node(env.NODE_NAME) {
  // clean /tmp dir from *.impex files
  sh "rm -rf /tmp/*.impex"
  //cleanup workspace
  cleanWs()
  //set build description
  currentBuild.description = "Test branch ${GIT_BRANCH}"
  //checkout to WORKSPACE
  //if target branch is defined - makes the local merge; else - just checkout
  stage("Checkout") {
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          if (env.TARGET_BRANCH) {
            checkout(
              [$class: 'GitSCM',
                branches: [[name: "${env.GIT_COMMIT}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'CleanBeforeCheckout'], [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: "${env.TARGET_BRANCH}"]], [$class: 'LocalBranch', localBranch: "${env.TARGET_BRANCH}"]],
               submoduleCfg: [],
               userRemoteConfigs: [[credentialsId: "${env.BB_CREDENTIALS}", url: "${env.GIT_URL}"]]])
          } else {
            checkout([$class: 'GitSCM',
              branches: [[name: "${env.GIT_COMMIT}"]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
              userRemoteConfigs: [[credentialsId: "${env.BB_CREDENTIALS}", url: "${env.GIT_URL}"]]])
          }
          true
        } catch(err){
          echo "Failed to checkout from Git. Will try again in 30 sec"
          sleep(30)
          false
        }
      }
    }
  }

  //loads the test module
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  //loads the DB module
  dbModule = load "ci/jenkins/pipeline/modules/db_module.groovy"
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //Loads the environment properties
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"

  //prepare hybris directory. Get more info in the test module (hybris_module.groovy)
  stage("Prepare Hybris directory"){
    hybrisModule.testPrepareHybrisDir()
  }

  //prepare hybris. Get more info in the test module (hybris_module.groovy)
  stage("Prepare env"){
    hybrisModule.testPrepareHybris("develop")
  }

  //create DB schema to run the tests. Get more info in the DB module (db_module.groovy)
  stage("Create DB schema") {
    if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
      dbModule.createSchema()
      dbModule.addDbPropsForHybris()
    }
  }

  try {
    //start test
    stage("Run test") {
        sh """
            cd ${CUSTOM_ANT_SCRIPT_DIR}
            . ./setantenv.sh
            ant unittests -Dstandalone.javaoptions=-javaagent:'\\\${jacoco.agent.path}=destfile=\\\${sonar.jacoco.reportPaths}'
            ant integrationtests -Dstandalone.javaoptions=-javaagent:'\\\${jacoco.agent.path}=destfile=\\\${sonar.jacoco.itReportPaths}'
            ant sonar -Dsonar.branch=${TARGET_BRANCH} -Dsonar.analysis.mode=preview
        """
    }
  } catch(err) {
    throw err
    currentBuild.result = "FAILED"
    error "Sonar tests failed"
  } finally {
     //drop created schema. Get more info in db_module.groovy
    stage("Drop DB schema") {
      if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
        dbModule.dropSchema()
      }
    }

   //clean the WORKSPACE. Get more info in hybris_module.groovy
    stage("Clean WORKSPACE") {
      hybrisModule.cleanWorkspace()
    }
  }

}
