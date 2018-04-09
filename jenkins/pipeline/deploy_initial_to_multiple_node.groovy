/*

properties([parameters([string(defaultValue: '', description: '', name: 'ENVIRONMENT'),
                        string(defaultValue: '', description: '', name: 'GIT_URL'),
                        string(defaultValue: 'develop', description: '', name: 'GIT_BRANCH'),
                        string(defaultValue: 'latest', description: '', name: 'ARTIFACT_ID'),
                        credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false),
                        string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform/custom/buildscripts/resources/buildscripts/ant',
                        description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
                        string(defaultValue: 'pr_tests', description: 'environment for spock tests', name: 'TEST_ENVIRONMENT'),
                        string(defaultValue: 'jslaves', description: 'node to run spock test', name: 'TEST_NODE'),
                        booleanParam(defaultValue: false, description: 'To update solr cloud schema', name: 'UPDATE_SOLR'),
                        booleanParam(defaultValue: false, description: '', name: 'SPOCK_TEST_RUN'),
                        booleanParam(defaultValue: true, description: 'Parameter to run autotests after deployment', name: 'RUN_AUTOTESTS'),
                        booleanParam(defaultValue: true, description: 'to skip the build if there is no changes', name: 'SKIP_IF_NO_CHANGES'),
                        string(defaultValue: '', description: 'Custom user email - this email list delimited by comma is sending email after chosen actionis finished. cc:EMAIL - to include to CC', name: 'USER_MAIL'),
                        text(defaultValue: '''https://server:port/hybrisacceleratorstorefront
                    https://server:port/hac
                    https://server:port/backoffice''', description: 'Site URLs', name: 'SITE_URLS')]), pipelineTriggers([])])


*/

node(){
  //cleanup workspace
  cleanWs()
  //Map with server types
  // the same names should be in ENV.properties for each env
  serversMap = ["be" : null, "fe" : null, "batch" : null]
  timeout(time:30, unit: 'MINUTES'){
    waitUntil{//do checkout until it is successful
      try{
        checkout([$class: 'GitSCM', branches: [[name: env.GIT_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
        true
      } catch(err){
        echo "Failed to checkout from Git. Will try again in 30 sec"
        sleep(30)
        false
      }
    }
  }

  props = readProperties file: "ci/conf/global.properties"
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  rnModule = load "ci/jenkins/pipeline/modules/release_notes_module.groovy"
  templateModule = load "ci/jenkins/pipeline/modules/emailtemplates_module.groovy"
  keys = serversMap.keySet() as List
  //set Manual test result, Job URI as global
  MNTestBuildResult = null
  MNTestBuildUrl = null

  //variable for confluence release notes page id
  kb_rn_page_id = ""

  //read builprops file in order to send artifact release in email
  buildprops = ""
  try{
    buildprops = rnModule.readBuildProperties()
  } catch(err){
    echo "Error during build props read"
  }
  // add description to build
  currentBuild.description = "${GIT_BRANCH}\n${buildprops.BUILD_ID}"
  //stop building if there was no new artifact id
  if (rnModule.readArtifactSHA(buildprops.BUILD_ID) == rnModule.readCurrentSHA() && (! env.SKIP_IF_NO_CHANGES || env.SKIP_IF_NO_CHANGES == "true")) {
    hybrisModule.sendNoNewArtifactEmail()
    error "There is no new artifact. Stop rebuild"
  }

  hybrisModule.sendDeployStartEmail()

  try {

    try {

      stage("Get servers"){
        for (key in keys) {
          try {
            serversMap[key] = envprops[key].split(",")
          } catch (err) {
            echo "Fail: Not found"
            echo key
            }
          }
        }

      stage("Stop All servers") {
        serverList = [:]
        for (key in keys) {
          if (serversMap[key] != null) {
            for (srvName in serversMap[key]) {
              serverList[srvName] = hybrisModule.stopRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
            }
          }
        }
          parallel serverList
      }

      stage("Update codebase"){
        serverList = [:]
        artifactsPath = "${env.GIT_BRANCH}/${buildprops.BUILD_ID}"
        //update codebase
        //fix cluster.node.groups = servergroup
        //update localextensions.xml
        for (key in keys) {
          if (serversMap[key] != null) {
            for (srvName in serversMap[key]) {
              srvType = key
              serverList[srvName] = hybrisModule.updateRemoteServerParallel(envprops.SSHAGENT_ID, artifactsPath, srvName, props.DEPLOY_USER, srvType)
            }
          }
        }
          parallel serverList
      }

      stage("Solr update and index restore"){
          if (env.UPDATE_SOLR == "true"){
            build job: 'manage_solr_update', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'GIT_BRANCH', value: rnModule.readArtifactSHA(buildprops.BUILD_ID)), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
          }
          build job: 'manage_solr_dump_restore', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'GIT_BRANCH', value: env.GIT_BRANCH), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
      }

      stage("Init on BE server") {
        timestamps {
        hybrisModule.initOnRemoteServer(envprops.SSHAGENT_ID, serversMap["be"][0], props.DEPLOY_USER, "be")
        }
        if (currentBuild.result == "FAILURE"){
          MNTestBuildResult = null
          MNTestBuildUrl = null
          error "DB Init failed"
        }
      }

      stage("Start All servers") {
        serverList = [:]
        for (key in keys) {
          if (serversMap[key] != null) {
            for (srvName in serversMap[key]) {
              serverList[srvName] = hybrisModule.startRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
            }
          }
        }
          parallel serverList
      }

      } catch (err) {
        currentBuild.result = "FAILURE"
        error "Deploy to ${ENVIRONMENT} was failed"
      }

      MNTestBuildResult = null
      MNTestBuildUrl = null

    if (currentBuild.result == null) {
      stage("Upload Static Content"){
        if (envprops.COPY_STATIC_TO_WEB.toLowerCase() == "true"){
          hybrisModule.moveWebStaticContent()
        }
      }
      stage("Spock tests run"){
        if (env.SPOCK_TEST_RUN=="true"){
          // Get git commit from artifacts
          GIT_COMMIT = rnModule.readArtifactSHA(buildprops.BUILD_ID)
            MNTestBuild = build job: 'ci_manual_test', parameters: [string(name: 'ENVIRONMENT', value: env.TEST_ENVIRONMENT),
                                                                  string(name: 'GIT_URL', value: env.GIT_URL),
                                                                  string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
                                                                  string(name: 'GIT_COMMIT', value: GIT_COMMIT),
                                                                  credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
                                                                  string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
                                                                  string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
                                                                  string(name: 'PARAMETERS', value: '${HYBRIS_ROOT}/hybris/bin/platform'),
                                                                  string(name: 'TARGET_BRANCH', value: env.GIT_BRANCH),
                                                                  string(name: 'NODE_NAME', value: env.TEST_NODE),
                                                                  string(name: 'HOST_NAME', value: envprops.ENTRY_SERVER),
                                                                  string(name: 'HOST_PORT', value: envprops.ENTRY_PORT),
                                                                  string(name: 'USER_MAIL', value: "${props.EMAIL_QA_GROUP},cc:${props.EMAIL_DEV_GROUP}")], propagate: false

          MNTestBuildResult = MNTestBuild.result
          MNTestBuildUrl = MNTestBuild.absoluteUrl
          echo MNTestBuildResult
          echo MNTestBuildUrl
        }
      }
    }

  } catch (err){
    currentBuild.result = "FAILURE"
    throw err
    error "Tests failed"
  } finally {
    stage("Prepare release notes"){
      rnModule.generateReleaseNotes()
      GIT_COMMIT = rnModule.readArtifactSHA(buildprops.BUILD_ID)
      rnModule.writeSHAtoJenkins(GIT_COMMIT)
    }
    hybrisModule.sendEmail(currentBuild.result, MNTestBuildResult, MNTestBuildUrl)

    if ((currentBuild.result == null) && (env.RUN_AUTOTESTS == "true")){
        build job: 'auto_test_run', parameters: [string(name: 'Environment', value: env.ENVIRONMENT)], wait: false
    }

  }

}
