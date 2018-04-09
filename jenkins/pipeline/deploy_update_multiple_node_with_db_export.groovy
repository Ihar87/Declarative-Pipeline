/*

properties([parameters([string(defaultValue: '', description: '', name: 'ENVIRONMENT'),
                        string(defaultValue: '', description: '', name: 'GIT_URL'),
                        string(defaultValue: 'develop', description: '', name: 'GIT_BRANCH'),
                        string(defaultValue: 'latest', description: '', name: 'ARTIFACT_ID'),
                        booleanParam(defaultValue: true, description: 'to update DB', name: 'UPDATE_DB'),
                        credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: 'gitlab', description: '', name: 'BB_CREDENTIALS', required: false),
                        string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform/custom/buildscripts/resources/buildscripts/ant',
                        description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
                        string(defaultValue: 'db_temp', description: 'environment for temp database', name: 'DBTEMP_ENVIRONMENT'),
                        booleanParam(defaultValue: false, description: '', name: 'CLEAN_USERS'),
                        booleanParam(defaultValue: false, description: '', name: 'CLEAN_ORDERS'),
                        booleanParam(defaultValue: true, description: 'to export dump before env was updated', name: 'EXPORT_BEFORE_UPDATE'),
                        booleanParam(defaultValue: true, description: 'to update db', name: 'UPDATE_DB'),
                        booleanParam(defaultValue: true, description: 'To update solr cloud schema', name: 'UPDATE_SOLR'),
                        string(defaultValue: 'pr_tests', description: 'environment for spock tests', name: 'TEST_ENVIRONMENT'),
                        string(defaultValue: 'jslaves', description: 'node to run spock test', name: 'TEST_NODE'),
                        booleanParam(defaultValue: true, description: '', name: 'SPOCK_TEST_RUN'),
                        booleanParam(defaultValue: true, description: 'Parameter to run autotests after deployment', name: 'RUN_AUTOTESTS'),
                        booleanParam(defaultValue: true, description: 'to skip the build if there is no changes', name: 'SKIP_IF_NO_CHANGES'),
                        string(defaultValue: '', description: 'Custom user email - this email list delimited by comma is sending email after chosen actionis finished. cc:EMAIL - to include to CC', name: 'USER_MAIL'),
                        text(defaultValue: '''https://server:port/hybrisacceleratorstorefront
                    https://server:port/hac
                    https://server:port/backoffice''', description: 'Site URLs', name: 'SITE_URLS')]), pipelineTriggers([])])


*/
//TODO add environment from
node(){
  //cleanup workspace
  cleanWs()
  //Map with server types
  // the same names should be in ENV.properties for each env
  serversMap = ["be" : null, "fe" : null, "batch" : null]
  timeout(time:30, unit: 'MINUTES'){
    waitUntil{// upload index to solr
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
  //Loads the release notes module
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

        stage("Create snapshot from current state"){
          if (env.EXPORT_BEFORE_UPDATE=="true"){
            build job: 'manage_create_db_dump', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
                                                      string(name: 'DBTEMP_ENVIRONMENT', value: 'db_temp'),
                                                      string(name: 'GIT_URL', value: env.GIT_URL),
                                                      string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
                                                      credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
                                                      booleanParam(name: 'CLEAN_USERS', value: env.CLEAN_USERS.toBoolean()),
                                                      booleanParam(name: 'CLEAN_ORDERS', value: env.CLEAN_ORDERS.toBoolean())]
          }
        }

        updateSrvName = serversMap["be"][0]

        stage("Stop BE node"){
          hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, updateSrvName, props.DEPLOY_USER)
        }

        stage("Deploy new codebase"){
          artifactsPath = "${env.GIT_BRANCH}/${buildprops.BUILD_ID}"
          hybrisModule.updateRemoteServer(envprops.SSHAGENT_ID, artifactsPath, updateSrvName, props.DEPLOY_USER, "be")
        }

        stage("Solr update and index restore"){
            if (env.UPDATE_SOLR == "true"){
              build job: 'manage_solr_update', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'GIT_BRANCH', value: rnModule.readArtifactSHA(buildprops.BUILD_ID)), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
            }
            build job: 'manage_solr_dump_restore', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT), string(name: 'GIT_BRANCH', value: env.GIT_BRANCH), credentials(description: '', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS)]
        }

        stage("Run update"){
          if (env.UPDATE_DB=="true"){
            hybrisModule.incrementalUpdateOnRemoteServer(envprops.SSHAGENT_ID, updateSrvName, props.DEPLOY_USER, "be")
            if (currentBuild.result == "FAILURE"){
              MNTestBuildResult = null
              MNTestBuildUrl = null
              error "DB Update failed"
            }
          }
        }


        stage("Start server"){
          hybrisModule.startRemoteServer(envprops.SSHAGENT_ID, updateSrvName, props.DEPLOY_USER)
        }

        stage("Update codebase on other nodes"){
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                srvType = key
                if (srvName != updateSrvName){
                    stage("Stop node on ${srvName}"){
                      hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                    }
                    stage("Update ${srvName}"){
                      artifactsPath = "${env.GIT_BRANCH}/${buildprops.BUILD_ID}"
                      hybrisModule.updateRemoteServer(envprops.SSHAGENT_ID, artifactsPath, srvName, props.DEPLOY_USER, srvType)
                    }
                    stage("Start node on  ${srvName}"){
                      hybrisModule.startRemoteServer(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                    }
                }
              }
            }
          }
        }

      } catch (err) {
        currentBuild.result = "FAILURE"
        throw(err)
        error "Deploy to ${ENVIRONMENT} was failed"
      }

      stage("Upload Static Content"){
        if (envprops.COPY_STATIC_TO_WEB.toLowerCase() == "true"){
          hybrisModule.moveWebStaticContent()
        }
      }

    MNTestBuildResult = null
    MNTestBuildUrl = null

    if (currentBuild.result == null) {
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
      if (currentBuild.result == null) {
          GIT_COMMIT = rnModule.readArtifactSHA(buildprops.BUILD_ID)
          rnModule.writeSHAtoJenkins(GIT_COMMIT)
      }
    }
    hybrisModule.sendEmail(currentBuild.result, MNTestBuildResult, MNTestBuildUrl)

    if ((currentBuild.result == null) && (env.RUN_AUTOTESTS == "true")){
        build job: 'auto_test_run', parameters: [string(name: 'Environment', value: env.ENVIRONMENT)], wait: false
    }
  }

}
