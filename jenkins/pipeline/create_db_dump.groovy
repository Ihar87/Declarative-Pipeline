//This pipeline is to create DB dump, archive medias directory and upload them to artifactory storage

/*properties([parameters([string(defaultValue: '', description: '', name: 'ENVIRONMENT'),
                        string(defaultValue: '', description: '', name: 'GIT_URL'),
                        string(defaultValue: 'develop', description: '', name: 'GIT_BRANCH'),
                        credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false),
                        string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform/custom/buildscripts/resources/buildscripts/ant',
                        description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
                        string(defaultValue: 'pr_tests', description: 'environment for spock tests', name: 'TEST_ENVIRONMENT'),
                        string(defaultValue: 'jslaves', description: 'node to run spock test', name: 'TEST_NODE'),
                        booleanParam(defaultValue: false, description: '', name: 'SPOCK_TEST_RUN')
                        ]), pipelineTriggers([])])

*/

//Import class to get formatted date
import java.text.SimpleDateFormat

node(){
  currentBuild.description=env.ENVIRONMENT

  // Cleans the WORKSPACE and checkouts the git repo
  stage("Checkout git repo") {
    //cleanup workspace
    cleanWs()
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          checkout([$class: 'GitSCM',
            branches: [[name: "${env.GIT_BRANCH}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: "${env.BB_CREDENTIALS}", url: "${env.GIT_URL}"]]])
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

  //get folder name to store the DB artifacts
  def dateFormat = new SimpleDateFormat("yyyyMMdd")
  def date = new Date()
  fldrName = dateFormat.format(date)+"_"+env.BUILD_ID

  parallel(
    "parallel: create medias artifact": {
      stage("Create medias artifact"){
        hybrisModule.createMediasArtifact()
      }
    },
    "parallel: create DB dump":{
      stage("Create DB dump"){
        dbModule.createDBDump(toArchive=false)
      }
    }
    )

  //Loads the environment properties
  sourceprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  //Loads the temp DB environment properties
  envprops = readProperties file: "ci/conf/${DBTEMP_ENVIRONMENT}.properties"

  stage("Data cleanup"){
    withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      dbModule.recreateSchema(USERNAME, USERPASSWORD)
      dbModule.importDBDump()
      dbModule.cleanupData()
      dbModule.createDBDump()
      dbModule.dropSchema(USERNAME)
    }
  }

  //Loads the environment properties
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"




  stage("Upload to Storage"){
    hybrisModule.uploadDBArtifacts(fldrName)
  }

  //clean the WORKSPACE
   stage("Clean WORKSPACE") {
     hybrisModule.cleanWorkspace()
   }
}
