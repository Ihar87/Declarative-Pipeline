//pipeline to run master tenant init. All jenkins job parameters are in hybris_module.groovy file
// + String ENV_DUMP with env name to get dump
// + string DBTEMP_ENVIRONMENT with default value db_temp
// + string DUMP_ARTIFACT_ID with default artifact id (latest)


//running on separape node
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

  //loads the module
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  //loads the DB module
  dbModule = load "ci/jenkins/pipeline/modules/db_module.groovy"
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //Loads the environment properties
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  //Loads the temp DB environment properties
  sourceprops = readProperties file: "ci/conf/${DBTEMP_ENVIRONMENT}.properties"

  //prepare hybris directory. Get more info in the module
  stage("Prepare Hybris directory") {
    hybrisModule.testPrepareHybrisDir()
  }

  //prepare hybris. Get more info in the module
  stage("Prepare env"){
    hybrisModule.testPrepareHybris("develop")
  }

  //create DB schema to run the tests
  stage("Create DB schema") {
    if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
      dbModule.createSchema()
      dbModule.addDbPropsForHybris()
    }
  }

  try {
    stage("Import Dump data") {
      dbModule.downloadDump(env.ENV_DUMP)
      envprops.DB_USER_NAME = dbModule.generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
      envprops.DB_USER_PASSWORD = dbModule.generateDbPassword("${env.JOB_NAME}","${env.BUILD_ID}")
      envprops.DB_SCHEMA_NAME = envprops.DB_USER_NAME
      dbModule.importDBDump()
      hybrisModule.importMediasArtifact(env.ENV_DUMP)
    }

    //start yunit tenant init and analyze the results of init using log parser plugin
    stage("Run update with result analyze") {
        timestamps {
        sh """
            cd ${CUSTOM_ANT_SCRIPT_DIR}
            . ./setantenv.sh
            ant incrementalupdate
        """
        }
        step([$class: 'LogParserPublisher', failBuildOnError: true, parsingRulesPath: props.LOG_PARSER_RULES, useProjectRule: false])
        if (currentBuild.result == "FAILURE"){
          error "DB Update failed"
        }
    }
  } catch(err) {
    throw err
    currentBuild.result = "FAILURE"
    error "Incremental update tests failed"
  } finally {
    //drop created schema
    stage("Drop DB schema") {
      if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
        dbModule.dropSchema()
      }
    }

    //clean the WORKSPACE
    stage("Clean WORKSPACE") {
      hybrisModule.cleanWorkspace()
    }
  }

}
