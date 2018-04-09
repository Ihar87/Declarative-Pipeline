//Properties to create parameters for job. Just create the pipeline job and add this section as a script. Start the job.
/*properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '')),
    gitLabConnection(''), parameters([string(defaultValue: '', description: 'env name', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'GIT URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    string(defaultValue: '', description: 'git commit to checkout the current commit', name: 'GIT_COMMIT'),
    string(defaultValue: 'init', description: 'DB preparation method', name: 'DB_PREPARE_METHOD'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: '', description: 'hybris root directory where the artifacts will be restored', name: 'HYBRIS_ROOT'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform/custom/buildscripts', description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
    string(defaultValue: '', description: 'Build ID of main job', name: 'MAIN_BUILD_ID'),
    string(defaultValue: '', description: 'Source branch', name: 'SOURCE_BRANCH'),
    string(defaultValue: '', description: 'User mail', name: 'USER_MAIL'),
    string(defaultValue: '', description: 'Jenkins node label name', name: 'NODE_NAME'),
    string(defaultValue: '', description: 'Jenkins CI node label name', name: 'TEST_NODE'),
    string(defaultValue: '', description: 'Hybris env name for test', name: 'TEST_ENVIRONMENT'),
    string(defaultValue: '', description: 'Target branch for test', name: 'TEST_TARGET_BRANCH'),
    booleanParam(defaultValue: false, description: '', name: 'SPOCK_TEST_RUN'),
    booleanParam(defaultValue: false, description: 'To keep FV running for QA or DEV verification', name: 'KEEP_FV_RUNNING'),
    booleanParam(defaultValue: false, description: '', name: 'FV_QA_REVIEW'),
    text(defaultValue: '', description: 'Site URLs', name: 'SITE_URLS'),
    string(defaultValue: '', description: 'Dump from this env will be used', name: 'ENV_DUMP'),
    string(defaultValue: 'db_temp', description: 'temp env that was used as a cleaner', name: 'DBTEMP_ENVIRONMENT'),
    string(defaultValue: 'latest', description: 'Dump artifact ID', name: 'DUMP_ARTIFACT_ID')]),
    pipelineTriggers([])])*/

//Pipeline deploys to single node

node(env.NODE_NAME) {
  //is QA review is approved
  var_QA_review = true
  // is dev review was Approved
  var_DEV_review = true
  currentBuild.description = "Deploy branch ${SOURCE_BRANCH}\n${DB_PREPARE_METHOD}"
  stage("Checkout") {
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          if (env.TARGET_BRANCH) {
            checkout(
              [$class: 'GitSCM',
                branches: [[name: env.GIT_COMMIT]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'CleanBeforeCheckout'], [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: env.TARGET_BRANCH]], [$class: 'LocalBranch', localBranch: env.TARGET_BRANCH]],
               submoduleCfg: [],
               userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
          } else {
            echo "checkout branch"
            checkout([$class: 'GitSCM',
              branches: [[name: env.GIT_BRANCH]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
              userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
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
  //Loads the template module
  templateModule = load "ci/jenkins/pipeline/modules/emailtemplates_module.groovy"
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //Loads the environment properties
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  //Loads the temp DB environment properties
  sourceprops = readProperties file: "ci/conf/${DBTEMP_ENVIRONMENT}.properties"
  //Loads solr module
//  solrModule = load "ci/jenkins/pipeline/modules/solr_module.groovy"


  stage("Send email") {
    if (env.KEEP_FV_RUNNING == "true") {
      hybrisModule.sendEmailFV("preparing", env.USER_MAIL)
    }
  }

  stage("Stop hybris if started"){
    hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
  }
  stage("Stops solr if started"){
    try{
      solrModule.stopFVSolr()
    } catch (err){
      echo "Failed to stop solr"
    }
  }

try{
  stage("Update codebase and prepare"){
    def aPath = ""
    if ("${props.ARTIFACT_STORAGE}" == 'nexus') {
      aPath = ""
    } else if ("${props.ARTIFACT_STORAGE}" == 'ftp') {
      aPath = "${env.GIT_BRANCH}/${env.MAIN_BUILD_ID}"
    } else if ("${props.ARTIFACT_STORAGE}" == 'local') {
      aPath = "${env.GIT_BRANCH}/${env.MAIN_BUILD_ID}"
    } else if ("${props.ARTIFACT_STORAGE}" == 's3') {
      aPath = "${env.GIT_BRANCH}/${env.MAIN_BUILD_ID}"
    }
    echo aPath
    hybrisModule.updateRemoteServer(envprops.SSHAGENT_ID, aPath, envprops.SERVER_NAME, envprops.USER_NAME)
  }

  stage("Start Solr server"){
    solrModule.startFVSolr()
  }

/*
  stage("Import Solr Index"){
    SERVER_NAME = sh (script: props.HOSTNAME_CHECK, returnStdout: true).trim()
    build job: 'manage_solr_dump_restore', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'FV_SERVER_NAME', value: SERVER_NAME), string(name: 'GIT_BRANCH', value: env.GIT_BRANCH), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
  }
*/

  //create DB schema to run the tests
  stage("Create DB schema") {
    if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
      dbModule.createSchema()
      dbModule.addDbPropsForHybris()
    }
  }

  stage("Import Dump data") {
    if (env.DB_PREPARE_METHOD == "update"){
      dbModule.downloadDump(env.ENV_DUMP)
      envprops.DB_USER_NAME = dbModule.generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
      envprops.DB_USER_PASSWORD = dbModule.generateDbPassword("${env.JOB_NAME}","${env.BUILD_ID}")
      envprops.DB_SCHEMA_NAME = envprops.DB_USER_NAME
      dbModule.importDBDump()
      hybrisModule.importMediasArtifact(env.ENV_DUMP)
    }
  }


  stage("Prepare DB"){
    if (env.DB_PREPARE_METHOD == "update"){
      timestamps {
      hybrisModule.incrementalUpdateOnRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
      }
    }
    if (env.DB_PREPARE_METHOD == "init"){
      timestamps {
      hybrisModule.initOnRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
      }
    }
    if (currentBuild.result == "FAILURE"){
      error "DB Preparation failed"
    }
  }


  stage("Start server"){
    hybrisModule.startRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
  }
SERVER_NAME = sh (script: props.HOSTNAME_CHECK, returnStdout: true).trim()


    try{
      stage("Run manual tests") {
        stash includes: 'ci/jenkins/git_utils.py', name: 'git_utils'
        if (env.SPOCK_TEST_RUN == "true"){
          SERVER_NAME = sh (script: props.HOSTNAME_CHECK, returnStdout: true).trim()
          build job: 'ci_manual_test', parameters: [string(name: 'ENVIRONMENT', value: env.TEST_ENVIRONMENT),
                                                  string(name: 'GIT_URL', value: env.GIT_URL),
                                                  string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
                                                  string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                                  credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
                                                  string(name: 'HYBRIS_ROOT', value: env.HYBRIS_ROOT),
                                                  string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
                                                  string(name: 'PARAMETERS', value: '${HYBRIS_ROOT}/hybris/bin/platform'),
                                                  string(name: 'TARGET_BRANCH', value: env.TEST_TARGET_BRANCH),
                                                  string(name: 'NODE_NAME', value: env.TEST_NODE),
                                                  string(name: 'HOST_NAME', value: SERVER_NAME),
                                                  string(name: 'HOST_PORT', value: '9002'),
                                                  string(name: 'USER_MAIL', value: env.USER_MAIL),]
        }else{
        }
      }
    } catch(err){
      currentBuild.result = "FAILURE"
      //error "Manual tests failed"
    }
    stage("Send email") {
      if (env.KEEP_FV_RUNNING == "true") {
        hybrisModule.sendEmailFV("started", env.USER_MAIL)
      }
    }


    stage("Wait for accept") {
      if (env.KEEP_FV_RUNNING == "true") {
        if (env.FV_QA_REVIEW == "true"){
          try {
            hybrisModule.inputWait(props.FV_QA_TIMEOUT_VALUE.toInteger(), props.FV_QA_TIMEOUT_UNIT, 'Was this Feature verification acceptable?')
          } catch (InterruptedException x) {
            echo "Was Aborted"
            var_QA_review = false
          }
        } else {
          try{
            hybrisModule.inputWait(props.FV_DEV_TIMEOUT_VALUE.toInteger(), props.FV_DEV_TIMEOUT_UNIT, 'Was this Feature verification acceptable?')
          } catch (InterruptedException x) {
            echo "Was Aborted"
            var_DEV_review = false
          }
        }
        if (! var_QA_review){
          try{
            hybrisModule.sendEmailFV("qa_rejected", env.USER_MAIL)
            hybrisModule.inputWait(props.FV_QA_REJECTED_TIMEOUT_VALUE.toInteger(), props.FV_QA_REJECTED_TIMEOUT_UNIT, 'Was this Feature verification acceptable?')
          } catch (InterruptedException x){
            echo "Was Aborted"
            var_DEV_review = false
          }
        }
        if (!(var_DEV_review && var_QA_review)){
          currentBuild.result = "FAILURE"
          error "FV was rejected"
        }
      }

    }

  } catch (err){
    currentBuild.result = "FAILURE"
    throw err
  } finally {
    if (env.KEEP_FV_RUNNING == "true") {
      if (currentBuild.result == null){
        hybrisModule.sendEmailFV("finished", env.USER_MAIL)
      } else {
        hybrisModule.sendEmailFV("failed", env.USER_MAIL)
      }
    }
    hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
    //drop created schema
    stage("Drop DB schema") {
      if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
        try{
          hybrisModule.killHybrisUpdateProcesses(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
        } catch (errKill) {
          echo "killed processes"
        }
        dbModule.dropSchema()
      }
    }
    //Stop solr standalone
    try{
      solrModule.stopFVSolr()
    } catch (err){
      echo "Failed to stop solr"
    }

    stage("Clean Workspace") {
      hybrisModule.cleanWorkspace()
    }
  }




}
