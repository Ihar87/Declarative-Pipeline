/*
properties([
  buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '20', numToKeepStr: '')),
  parameters([
    choice(choices: ['env1', 'env2','fv_tests'], description: '', name: 'ENVIRONMENT'),
    choice(choices: ['fv1', 'fv2','fvN'], description: '', name: 'FV_SERVER_NAME'),
    choice(choices: ['list', 'update', 'revert'], description: '''action list:\nlist - list properties to console log\nupdate - add property to properties file\nrevert - restore property file from the build''', name: 'ACTION'),
    booleanParam(defaultValue: false, description: 'backend nodes', name: 'be'),
    booleanParam(defaultValue: false, description: 'frontend nodes', name: 'fe'),
    booleanParam(defaultValue: false, description: 'batch nodes', name: 'batch'),
    choice(choices: ['/opt/hybris/hybris/config/local.properties', 'file2.properties', 'file3.properties'], description: '', name: 'FILE_NAME'),
    text(defaultValue: '', description: 'properties to add', name: 'PROPS_TO_ADD'),
    booleanParam(defaultValue: false, description: 'to restart nodes after property change', name: 'RESTART_NODES'),
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


  //check if FV env is chosen
  if (env.ENVIRONMENT == "fv_tests" && env.FV_SERVER_NAME == ""){
    error "Set FV server name"
  }


  props = readProperties file: "ci/conf/global.properties"
  envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
  keys = serversMap.keySet() as List
  restartList = []
  output = ""
  //restart list according to output
  restartIndicatorList = []

  currentBuild.description = "${ENVIRONMENT}: ${ACTION}"

  stage("Get servers"){
    if (env.ENVIRONMENT != "fv_tests"){
      for (key in keys) {
        try {
          if (env."${key}" == "true"){
            serversMap[key] = envprops[key].split(",")
          }
        } catch (err) {
          echo "Fail: Not found"
          echo key
        }
      }
    } else {
      keys.add('fv_tests')
      serversMap['fv_tests'] = env.FV_SERVER_NAME.split(",")
    }

  }

  stage("Action with property file"){
    for (key in keys) {
      if (serversMap[key] != null) {
        for (srvName in serversMap[key]) {
          if (env.ACTION == "list"){
            output = hybrisModule.propertyFileAction(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER, env.ACTION, env.FILE_NAME)
            echo "\n\n########### ${srvName} list ${FILE_NAME} properties ###########\n"
            echo output
            echo "\n\n#####################################################################################################################\n"
          } else if (env.ACTION == "revert"){
            output = hybrisModule.propertyFileAction(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER, env.ACTION, env.FILE_NAME)
            echo "\n\n########### ${srvName} revert ${FILE_NAME} ###########\n"
            echo output
            echo "\n\n############################################################################################################\n"
            if (output != "Nothing to restore"){
              restartIndicatorList.add(srvName)
              if (! restartList.contains(key)){
                restartList.add(key)
              }
            }
          } else if (env.ACTION == "update"){
            output = hybrisModule.propertyFileAction(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER, env.ACTION, env.FILE_NAME, env.PROPS_TO_ADD)
            echo "\n\n########### ${srvName} update ${FILE_NAME} ###########\n"
            echo output
            echo "\n\n############################################################################################################\n"
            restartIndicatorList.add(srvName)
            if (! restartList.contains(key)){
              restartList.add(key)
            }
          }
        }
      }
    }
  }

    stage("Email send before restart"){
      if (env.RESTART_NODES == "true" && restartIndicatorList.size() != 0){
        pRecepient =  getRec()
        if (env.PREACTION_EMAIL_LIST){
          pRecepient +=",${env.PREACTION_EMAIL_LIST}"
        }
        if (env.ENVIRONMENT != "fv_tests"){
          emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action : restart will be done.", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} nodes action restart will be done", to: pRecepient
        } else {
          emailext body: "Nodes ${FV_SERVER_NAME} of ${ENVIRONMENT}. \n Action : restart will be done.", recipientProviders: [], subject: "${FV_SERVER_NAME} nodes action restart will be done", to: pRecepient
        }

        sleep(10)
      }
    }

  stage("Restart Nodes"){
    if (env.RESTART_NODES == "true" && restartIndicatorList.size() != 0){
      try{
        if (env.PARALLEL == "true"){
          serverList = [:]
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                if (srvName in restartIndicatorList){
                  serverList[srvName] = hybrisModule.stopRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                }
              }
            }
          }
          parallel serverList
          serverList = [:]
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                if (srvName in restartIndicatorList){
                  serverList[srvName] = hybrisModule.startRemoteServerParallel(envprops.SSHAGENT_ID, srvName, props.DEPLOY_USER)
                }
              }
            }
          }
          parallel serverList
        } else {
          for (key in keys) {
            if (serversMap[key] != null) {
              for (srvName in serversMap[key]) {
                if (srvName in restartIndicatorList){
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
  }

  stage("Email sending"){
    if (env.RESTART_NODES == "true" && restartIndicatorList.size() != 0){
      pRecepient =  getRec()
      if (env.POSTACTION_EMAIL_LIST){
        pRecepient +=",${env.POSTACTION_EMAIL_LIST}"
      }
      if (currentBuild.result == null){
        if (env.ENVIRONMENT != "fv_tests"){
          emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action was: restart ", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} nodes action restart was performed", to: pRecepient
        } else {
          emailext body: "Nodes ${FV_SERVER_NAME} of ${ENVIRONMENT}. \n Action was: restart ", recipientProviders: [], subject: "${FV_SERVER_NAME} nodes action restart was performed", to: pRecepient
        }
      } else {
        if (env.ENVIRONMENT != "fv_tests"){
          emailext body: "Nodes ${restartList} of ${ENVIRONMENT.toUpperCase()}. \n Action was: restart \n Result: ${currentBuild.result} \n Please look into log ${BUILD_URL} ", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} nodes action restart was performed", to: pRecepient
        } else {
          emailext body: "Nodes ${FV_SERVER_NAME} of ${ENVIRONMENT}. \n Action was: restart \n Result: ${currentBuild.result} \n Please look into log ${BUILD_URL} ", recipientProviders: [], subject: "${FV_SERVER_NAME} nodes action restart was performed", to: pRecepient
        }
      }
    }
  }

}
