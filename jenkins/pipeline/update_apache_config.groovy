/*
properties([
  parameters([
    choice(choices: ['env1', 'env2'], description: '', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'do not change', name: 'GIT_URL'),
    string(defaultValue: 'develop', description: 'pipeline will be downloaded from this branch', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false)
  ]),
  pipelineTriggers([])
])
*/

node(){
  //cleanup workspace
  cleanWs()
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

  //Map with server types
  // the same names should be in ENV.properties for each env
  serversMap = ["be" : null, "fe" : null, "batch" : null]

  keys = serversMap.keySet() as List

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

  stage("Prepare apache config"){
    for (key in keys) {
      if (serversMap[key] != null) {
        srvType = "${key}".toUpperCase()
        paramList = envprops["SED_PARAMS"].split(",")
        for (tParam in paramList){
            srvParam = "${srvType}_${tParam}"
            if (envprops["${srvParam}"]){
                srvParamSed = envprops["${srvParam}"]
                sh """
                  sed -i -e "s/${srvParam}/${srvParamSed}/" ${props.REPO_APACHE_CONFIG_FOLDER}/env.conf
                """
            }
        }
        for (int srvId = 0; srvId < serversMap[key].size(); srvId++) {
          srvName = "${serversMap[key][srvId]}"
          srvConfId = srvId + 1
          srvHybServer = "${srvType}_HYB_SERVER${srvConfId}"
          sh """
            sed -i -e "s/${srvHybServer}/${srvName}/" ${props.REPO_APACHE_CONFIG_FOLDER}/env.conf
          """
        }
      }
    }
  }

  stage("Copy config to WEB servers and apply"){
    webServerList = envprops.WEB.split(",")
    for (serverName in webServerList){
      sshagent([envprops.WEB_SSHAGENT_ID]) {
        sh """
          ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "exit"
          rsync -av ${WORKSPACE}/${props.REPO_APACHE_CONFIG_FOLDER}/env.conf ${envprops.WEB_USER}@${serverName}:/tmp/${ENVIRONMENT}.conf
          ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "sudo su - -c 'rsync -av /tmp/${ENVIRONMENT}.conf ${envprops.WEB_CONFIG_FOLDER}/'"
          ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "sudo su - -c 'service httpd restart'"
          ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "rm -f /tmp/${ENVIRONMENT}.conf"
        """
      }
    }
  }

}
