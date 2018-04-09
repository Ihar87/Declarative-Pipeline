//Pipeline to import dump for developer in CI DB instance
/*
properties([
  parameters([
    string(defaultValue: 'pr_tests', description: 'do not change', name: 'ENVIRONMENT'),
    string(defaultValue: 'db_temp', description: 'do not change', name: 'DBTEMP_ENVIRONMENT'),
    string(defaultValue: '', description: 'do not change', name: 'GIT_URL'),
    string(defaultValue: 'develop', description: 'pipeline will be downloaded from this branch', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: 'prod', description: 'this env artifact will be downloaded', name: 'ENV_DUMP'),
    string(defaultValue: 'latest', description: '', name: 'DUMP_ARTIFACT_ID'),
    string(defaultValue: '', description: 'your user name, e.g. username_surname', name: 'DB_USERNAME')
  ]),
  pipelineTriggers([])
])
*/
node(){
  //cleanup workspace
  cleanWs()
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

  // add description to build
  currentBuild.description = "${DB_USERNAME}:${ENV_DUMP}/${DUMP_ARTIFACT_ID}"

  stage("Recreate schema"){
    dbModule.recreateSchema(env.DB_USERNAME, env.DB_USERNAME)
  }

  stage("Import Dump"){
    if (env.ENV_DUMP){
      dbModule.downloadDump(env.ENV_DUMP)
      envprops.DB_USER_NAME = env.DB_USERNAME
      envprops.DB_USER_PASSWORD = env.DB_USERNAME
      envprops.DB_SCHEMA_NAME = envprops.DB_USER_NAME
      dbModule.importDBDump()
    } else {
      echo "Just creating the schema"
    }
  }

  echo "Please use DB settings"
  echo """db.url=jdbc:oracle:thin:@server:1521:orcl\ndb.driver=oracle.jdbc.driver.OracleDriver\ndb.username=${DB_USERNAME}\ndb.password=${DB_USERNAME}\ndb.tableprefix=\noracle.statementcachesize=0\ndb.pool.maxActive=90\ndb.pool.maxIdle=90\ntenant.restart.on.connection.error=false"""

}
