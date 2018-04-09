/*
properties([gitLabConnection(''),
  parameters([choice(choices: ['fqa1', 'fqa2', 'uat1', 'uat2', 'perf', 'prod'], description: '', name: 'ENVIRONMENT'),
  string(defaultValue: 'master_radr_collection_default', description: 'collections list separated by comma to backup', name: 'COLLECTIONS_TO_BACKUP'),
  string(defaultValue: '', description: '', name: 'GIT_URL'),
  string(defaultValue: '', description: '', name: 'GIT_BRANCH'),
  booleanParam(defaultValue: true, description: 'to replace the current backup if such id is available', name: 'OVERRIDE'),
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

  currentBuild.description = "${ENVIRONMENT}: ${COLLECTIONS_TO_BACKUP}"

  stage("Backup collections"){
    collectionList = env.COLLECTIONS_TO_BACKUP.split(",")
    versionedList = props.SOLR_VERSIONED_BACKUPS.split(",")
    solrServer = envprops.SOLR.split(",")[0].trim()
    for (collection in collectionList){
      collection = collection.trim()
      sh "mkdir solr_backup"
      solrModule.solrIndexBackup(envprops.SOLR_SSHAGENT_ID, solrServer, props.SOLR_ROOT_USER, props.SOLR_USER, collection)
      sh "cd solr_backup && zip -qr ${collection}.zip ${collection} && rm -rf ${collection}"
      solrModule.uploadSolrBackup(props.SOLR_BACKUP_SSHAGENT_ID, props.SOLR_BACKUP_SERVER, props.SOLR_BACKUP_ARTIFACT_USER, collection)
    }
  }
}
