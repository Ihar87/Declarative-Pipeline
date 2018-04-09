//pipeline to run junit tests. All jenkins job parameters are in hybris_module.groovy file
//also need add
// HOST_NAME and HOST_PORT, USER_MAIL

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

  //prepare hybris directory. Get more info in the module
  stage("Prepare Hybris directory") {
    hybrisModule.testPrepareHybrisDir()
  }

  //prepare hybris. Get more info in the module
  stage("Prepare env"){
    hybrisModule.testPrepareHybris("develop", false)
  }

  //create DB schema to run the tests
  stage("Create DB schema") {
    if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
      dbModule.createSchema()
      dbModule.addDbPropsForHybris()
    }
  }

  //add hostname and port to local.properties
  stage("Add properties to local.properties"){
    sh """
      echo "ui.testing.target.hybris.host=${env.HOST_NAME}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
      echo "ui.testing.target.hybris.secure.port=${env.HOST_PORT}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
    """
  }

  try {
    //start test
    stage("Run test") {
        sh """
            cd ${CUSTOM_ANT_SCRIPT_DIR}
            . ./setantenv.sh
            ant manualtests
        """
    }

    //Copy test results to the WORKSPACE dir for analyze
    stage("Copy test results to WORKSPACE") {
        sh """
            mv ${HYBRIS_ROOT}/hybris/log/junit ${WORKSPACE}/log/
        """
    }

    //analyze the results of the test
    stage("Analyze results") {
      try{
        junit 'log/junit/*.xml'
      } catch (err){
        currentBuild.result = "FAILURE"
        emailext body: "Manual tests are failed for branch ${GIT_BRANCH}\nPlease check logs for job ${BUILD_URL}", recipientProviders: [], subject: "Manual tests are failed", to: env.USER_MAIL
        error "Manual tests failed"
      } finally {
        if (currentBuild.result==null){
          emailext body: "Manual tests are passed for branch ${GIT_BRANCH}\nPlease check logs for job ${BUILD_URL}", recipientProviders: [], subject: "Manual tests are passed", to: env.USER_MAIL
        }
        if ((currentBuild.result == "FAILURE") || (currentBuild.result == "UNSTABLE")){
          emailext body: "Manual tests are failed for branch ${GIT_BRANCH}\nPlease check logs for job ${BUILD_URL}", recipientProviders: [], subject: "Manual tests are failed", to: env.USER_MAIL
        }
      }
    }
  } catch(err){
    throw err
    currentBuild.result = "FAILURE"
    error "Manual tests failed"
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