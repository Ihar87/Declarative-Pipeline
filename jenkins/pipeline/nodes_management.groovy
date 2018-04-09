/*
properties([
  buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '20', numToKeepStr: '')),
  parameters([
    choice(choices: ['env1', 'env2'], description: '', name: 'ENVIRONMENT'),
    choice(choices: ['restart', 'stop'], description: '', name: 'ACTION'),
    booleanParam(defaultValue: false, description: 'restart backend nodes', name: 'be'),
    booleanParam(defaultValue: true, description: 'restart frontend nodes', name: 'fe'),
    booleanParam(defaultValue: false, description: 'restart batch nodes', name: 'batch'),
    booleanParam(defaultValue: true, description: 'to restart nodes in parallel', name: 'PARALLEL'),
    string(defaultValue: '', description: 'do not change', name: 'GIT_URL'),
    string(defaultValue: 'develop', description: 'pipeline will be downloaded from this branch', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: '', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: 'user@server.com,cc:usercc@server.com', description: '''to this email list delimited by comma will be sent email before to start chosen action\ncc:EMAIL to include to CC''', name: 'PREACTION_EMAIL_LIST'),
    string(defaultValue: 'user@server.com,cc:usercc@server.com', description: '''to this email list delimited by comma will be sent email after chosen action finished\ncc:EMAIL to include to CC''', name: 'POSTACTION_EMAIL_LIST'),
    string(defaultValue: '0', description: '''seconds to sleep before to restart next server\napplicable for non-parallel restart only''', name: 'SLEEP')
  ]),
  pipelineTriggers([])
])

*/


import hudson.model.User
import hudson.tasks.Mailer

@NonCPS
    def getRec(){
        uID = currentBuild.getRawBuild().getCauses()[0].getUserId()
        User u = User.get(uID)
        def umail = u.getProperty(Mailer.UserProperty.class)
        pRecepient =  umail.getAddress()
        return pRecepient
    }

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
  keys = serversMap.keySet() as List
  restartList = []

  currentBuild.description = "${ENVIRONMENT}: ${ACTION}"

  stage("Get servers"){
    for (key in keys) {
      try {
        if (env."${key}" == "true"){
          serversMap[key] = envprops[key].split(",")
          if (serversMap[key] != null){
            restartList.add(key)
          }
        }
      } catch (err) {
        echo "Fail: Not found"
        echo key
        }
      }
    }

    stage("Email send before"){
      pRecepient =  getRec()
      if (env.PREACTION_EMAIL_LIST){
        pRecepient +=",${env.PREACTION_EMAIL_LIST}"
      }
      emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action : ${ACTION} will be done.", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} nodes action ${ACTION} will be done", to: pRecepient
      sleep(10)
    }

  stage("Action on server"){
    try{
      if (env.ACTION == "stop"){
        if (env.PARALLEL == "true"){
          serverList = [:]
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                serverList[srvName] = hybrisModule.stopRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
              }
            }
          }
          parallel serverList
        } else {
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
              }
            }
          }
        }
      }

      if (env.ACTION == "restart"){
        if (env.PARALLEL == "true"){
          serverList = [:]
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                serverList[srvName] = hybrisModule.stopRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
              }
            }
          }
          parallel serverList
          serverList = [:]
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                serverList[srvName] = hybrisModule.startRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
              }
            }
          }
          parallel serverList
        } else {
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                hybrisModule.stopRemoteServer(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                hybrisModule.startRemoteServer(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                sleep env.SLEEP.toInteger()
              }
            }
          }
        }
      }
    } catch(err){
      //throw err
      currentBuild.result = "FAILURE"
    }
  }

  stage("Email sending"){
    pRecepient =  getRec()
    if (env.PREACTION_EMAIL_LIST){
      pRecepient +=",${env.PREACTION_EMAIL_LIST}"
    }
    if (currentBuild.result == null){
      emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action was: ${ACTION} ", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} nodes action ${ACTION} was performed", to: pRecepient
    } else {
      emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action was: ${ACTION} \n Result: ${currentBuild.result} \n Please look into log ${BUILD_URL} ", recipientProviders: [], subject: "[${currentBuild.result}] ${ENVIRONMENT.toUpperCase()} nodes action ${ACTION} was performed", to: pRecepient
    }

  }

}
