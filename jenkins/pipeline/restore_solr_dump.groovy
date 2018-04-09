/*
properties([gitLabConnection(''),
  parameters([
  choice(choices: ['fqa1', 'fqa2', 'uat1', 'uat2', 'perf', 'prod'], description: '', name: 'ENVIRONMENT'),
  choice(choices: ['fv1', 'fv2','fvN'], description: '', name: 'FV_SERVER_NAME'),
  string(defaultValue: 'master_radr_collection_default', description: 'collections list separated by comma to backup', name: 'COLLECTIONS_TO_BACKUP'),
  string(defaultValue: '', description: '', name: 'GIT_URL'),
  string(defaultValue: '', description: '', name: 'GIT_BRANCH'),
  credentials(credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false)]),
  pipelineTriggers([])])
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

  props = readProperties file: "ci/conf/global.properties"
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  solrModule = load "ci/jenkins/pipeline/modules/solr_module.groovy"
  //server where local.properties file will be checked for versioner solr collection id
  localpropsServer = ""

  currentBuild.description = "${ENVIRONMENT}"

  stage("Restore collections"){
    toRunRollback = true
    ifWasStartedByOtherProject = currentBuild.rawBuild.getCauses()[0].properties.upstreamProject
    if (ifWasStartedByOtherProject){
      echo "Was started by ${ifWasStartedByOtherProject} project"
      if (envprops.SOLR_RESTORE_ON_REBUILD == "true"){
        toRunRollback = true
      } else {
        toRunRollback = false
      }
    } else {
      echo "Was started manually"
    }
    if (toRunRollback){
      echo "Starting to restore solr collections"
      solrServer = ""
      collectionString = ""
      collectionList = ""
      configSetString = ""
      configSetList = ""
      if (env.ENVIRONMENT == "fv_tests" && env.FV_SERVER_NAME == ""){
        error "Set FV server name"
      }
      if (env.COLLECTIONS_TO_RESTORE == ""){
        collectionString = envprops.SOLR_RESTORE_LIST
        configSetString = envprops.SOLR_CONFIGSET_LIST
      } else {
        collectionString = env.COLLECTIONS_TO_RESTORE
        configSetString = env.CONFIGSET_LIST
      }
      collectionList = collectionString.split(",")
      versionedList = props.SOLR_VERSIONED_BACKUPS.split(",")
      configSetList = configSetString.split(",")
      if (env.ENVIRONMENT == "fv_tests"){
        solrServer = env.FV_SERVER_NAME
        localpropsServer = solrServer
      } else {
        solrServer = envprops.SOLR.split(",")[0].trim()
        localpropsServer = envprops.be.split(",")[0].trim()
      }
      echo collectionList.toString()
      echo solrServer
      hybrisModule.copyRemoteLocalProperty(props.DEPLOY_USER, localpropsServer)
      localprops = readProperties file: "${WORKSPACE}/local.properties"
      for (int i=0; i< collectionList.size(); i++){
        collection = collectionList[i].trim()
        if (envprops.SOLR_MODE == "single"){
          solrModule.restoreSolrDir(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER, collection)
          configSet = configSetList[i].trim()
          solrModule.restoreSolrBackup(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER, collection, configSet)
          solrModule.removeSolrTempDir(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER)
        }
        if (envprops.SOLR_MODE == "cloud"){
          solrModule.restoreSolrDir(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER, collection)
          solrModule.restoreSolrBackup(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER, collection)
          solrModule.removeSolrTempDir(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER)
        }

      }
      //Workarround cause of the bug: https://issues.apache.org/jira/browse/SOLR-11660
      // when cloased and solr has this fix - remove this restart
      if (envprops.SOLR_MODE == "cloud"){
        for (solrServer in envprops.SOLR.split(",")){
          solrModule.restartSolr(envprops.SOLR_SSHAGENT_ID, solrServer.trim(), props.SOLR_ROOT_USER)
          sleep(30)
        }
      }
      // End of Remove
    }
  }
}
