//Properties to create parameters for job. Just create the pipeline job and add this section as a script. Start the job.
/*properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '')),
    gitLabConnection(''), parameters([string(defaultValue: '', description: 'env name', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'GIT URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    string(defaultValue: '', description: 'git branch name from which sources will be loaded', name: 'LOAD_GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: '', description: 'hybris root directory where the artifacts will be restored', name: 'HYBRIS_ROOT'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/custom/buildscripts', description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
    string(defaultValue: 'latest', description: '', name: 'ARTIFACT_ID'),
    string(defaultValue: '', description: 'User mail', name: 'USER_MAIL'),
    string(defaultValue: '', description: 'Jenkins node label name', name: 'NODE_NAME')]),
    pipelineTriggers([])])*/

//Pipeline deploys to single node

node(env.NODE_NAME) {
  //cleanup workspace
  cleanWs()
  stage("Checkout") {
    echo "checkout branch"
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          checkout([$class: 'GitSCM',
            branches: [[name: env.LOAD_GIT_BRANCH]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
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
  //Loads reelase notes module
  rnModule = load "ci/jenkins/pipeline/modules/release_notes_module.groovy"
  //Loads solr module
  solrModule = load "ci/jenkins/pipeline/modules/solr_module.groovy"

  //to test the init (if commit is different)
  toTest = true
  buildprops = ""

  node(){
    //read builprops file in order to send artifact release in email

    try{
      buildprops = rnModule.readBuildProperties()
    } catch(err){
      echo "Error during build props read"
    }
    // add description to build
    currentBuild.description = "${GIT_BRANCH}\n${buildprops.BUILD_ID}"
    //read properties from previous build
    def exists = fileExists "${props.GIT_COMMIT_FOLDER}/testinit.sha"
    previousCommit = ""
    currentCommit = rnModule.readArtifactSHA(buildprops.BUILD_ID)
    if (exists){
      previousCommit = readFile "${props.GIT_COMMIT_FOLDER}/testinit.sha"
      //stop building if there was no new artifact id
      if (currentCommit == previousCommit) {
        toTest = false
        echo "!!! NO CHANGES"
      }
    }
    if (toTest){
      // add description to build
      currentBuild.description = "${GIT_BRANCH}\n${buildprops.BUILD_ID}"
    } else{
      currentBuild.description = "skipped"
    }
  }


  stage("Stop hybris if started"){
    if (toTest){
      hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
    }
  }

  stage("Stops solr if started"){
    if (toTest){
      try{
        solrModule.stopFVSolr()
      } catch (err){
        echo "Failed to stop solr"
      }
    }
  }

  stage("Update codebase and prepare"){
    if (toTest){
      def aPath = ""
      if ("${props.ARTIFACT_STORAGE}" == 'nexus') {
        aPath = ""
      } else if ("${props.ARTIFACT_STORAGE}" == 'ftp') {
        aPath = "${env.GIT_BRANCH}/${buildprops.BUILD_ID}"
      } else if ("${props.ARTIFACT_STORAGE}" == 'local') {
        aPath = "${env.GIT_BRANCH}/${buildprops.BUILD_ID}"
      }
      echo aPath
      hybrisModule.updateRemoteServer(envprops.SSHAGENT_ID, aPath, envprops.SERVER_NAME, envprops.USER_NAME)
    }
  }

  stage("Start Solr server"){
    if (toTest){
      solrModule.startFVSolr()
    }
  }

  stage("Import Solr Index"){
    if (toTest){
      SERVER_NAME = sh (script: props.HOSTNAME_CHECK, returnStdout: true).trim()
      build job: 'manage_solr_dump_restore', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'FV_SERVER_NAME', value: SERVER_NAME), string(name: 'GIT_BRANCH', value: env.GIT_BRANCH), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
    }
  }

  //create DB schema to run the tests
  stage("Create DB schema") {
    if (toTest){
      if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
        dbModule.createSchema()
        dbModule.addDbPropsForHybris()
      }
    }
  }

  stage("Initialize"){
    if (toTest){
      hybrisModule.initOnRemoteServer(envprops.SSHAGENT_ID, envprops.SERVER_NAME, envprops.USER_NAME)
    }
  }


  stage("Solr Stop"){
    if (toTest){
      //Stop solr standalone
      try{
        solrModule.stopFVSolr()
      } catch (err){
        echo "Failed to stop solr"
      }
    }
  }

  stage("Drop DB schema") {
    if (toTest){
      if (envprops.DB_TO_CREATE.toLowerCase() == "true"){
        dbModule.dropSchema()
      }
    }

  }

  stage("Clean Workspace") {
    hybrisModule.cleanWorkspace()
  }

}

node{
  stage("Send email"){
    if (toTest){
      try{
        rnModule.generateReleaseNotes(previousCommit, currentCommit)
      } catch(err) {
        echo "Failed to create release notes"
      }
      writeFile file: "testinit.sha", text: currentCommit
      sh """
        rm -f ${props.GIT_COMMIT_FOLDER}/testinit.sha
        mv testinit.sha ${props.GIT_COMMIT_FOLDER}
      """
      if (currentBuild.result == "FAILURE"){
        if (currentBuild.getPreviousBuild().result == "SUCCESS"){
          emailext attachmentsPattern: '*.xlsx', body: 'Colleagues,\n\tThe intialization from artifact with ID ${buildprops.BUILD_ID}" was failed. Please check logs for job ${BUILD_URL}.', subject: 'Initalization test from artifact was failed', to: env.USER_MAIL
        } else {
          emailext attachmentsPattern: '*.xlsx', body: 'Colleagues,\n\tThe intialization from artifact with ID ${buildprops.BUILD_ID}" is still failing. Please check logs for job ${BUILD_URL}.', subject: 'Initalization test from artifact still failing', to: env.USER_MAIL
        }

      } else {
        if (currentBuild.getPreviousBuild().result == "FAILURE") {
          emailext attachmentsPattern: '*.xlsx', body: 'Colleagues,\n\tThe intialization from artifact with ID ${buildprops.BUILD_ID}" was fixed. Please check logs for job ${BUILD_URL}.', subject: 'Initalization test from artifact - fixed', to: env.USER_MAIL
        }
      }
    }
  }



  stage("Clean Workspace") {
    hybrisModule.cleanWorkspace()
  }

}
