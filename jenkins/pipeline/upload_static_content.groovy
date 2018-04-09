/*
properties([
  parameters([
    choice(choices: ['env1', 'env2'], description: '', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'do not change', name: 'GIT_URL'),
    string(defaultValue: 'develop', description: 'pipeline will be downloaded from this branch', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: 'latest', description: '', name: 'ARTIFACT_ID')
  ]),
  pipelineTriggers([])
])
*/


node(){
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

  props = readProperties file: "ci/conf/global.properties"
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  //Loads the release notes module
  rnModule = load "ci/jenkins/pipeline/modules/release_notes_module.groovy"
  stage("Upload Static Content"){
    buildprops = ""
    try{
      buildprops = rnModule.readBuildProperties()
    } catch(err){
      echo "Error during build props read"
    }
    if (envprops.COPY_STATIC_TO_WEB.toLowerCase() == "true"){
      hybrisModule.moveWebStaticContent()
    }
  }

}
