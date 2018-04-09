//this is a module contains all needed methods


//Properties to create parameters for job. Just create the pipeline job and add this section as a script. Start the job.
/*properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '')),
    gitLabConnection(''), parameters([string(defaultValue: '', description: 'env name', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'GIT URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    string(defaultValue: '', description: 'git commit to checkout the current commit', name: 'GIT_COMMIT'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for AWS', name: 'AWS_CREDENTIALS', required: false),
    string(defaultValue: '', description: 'hybris root directory where the artifacts will be restored', name: 'HYBRIS_ROOT'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform/custom/buildscripts/resources/buildscripts/ant', description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform', description: 'parameters for the job', name: 'PARAMETERS'),
    string(defaultValue: '', description: 'Target branch to make local merge', name: 'TARGET_BRANCH'),
    string(defaultValue: '', description: 'Jenkins node label name', name: 'NODE_NAME')]),
    pipelineTriggers([])])*/

// Cleans the WORKSPACE and checkouts the git repo
def testGitCheckout() {
  timeout(time:30, unit: 'MINUTES'){
    waitUntil{// upload index to solr
      try{
        checkout(
          [$class: 'GitSCM',
            branches: [[name: "${env.GIT_COMMIT}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'CleanBeforeCheckout'], [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: "${env.TARGET_BRANCH}"]], [$class: 'LocalBranch', localBranch: "${env.TARGET_BRANCH}"]],
           submoduleCfg: [],
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

// sync hybris files to the right places
def testPrepareHybrisDir() {
  //Loads the global.properties file to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  // Check the hybris installation archive
  // Download from
  if ("${props.HYBRIS_S3}") {
    sh """
    shopt -s extglob
    if [ ! -d ${props.SOURCE_DIR} ]; then
      echo "Creating directory ${props.SOURCE_DIR}"
      mkdir -p ${props.SOURCE_DIR}
    fi
    """
    def exists = fileExists "${props.SOURCE_DIR}/${props.SOURCE_FILE}"
    if (! exists) {
      withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
        s3Download(file:"${props.SOURCE_DIR}/${props.SOURCE_FILE}", bucket:"${props.S3_BUCKET}", path:"${props.HYBRIS_S3_SOURCEDIR}/${props.SOURCE_FILE}", force:true)
      }
    } else {
      sh """
        rm -f ${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5
      """
      withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
        s3Download(file:"${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5", bucket:"${props.S3_BUCKET}", path:"${props.HYBRIS_S3_SOURCEDIR}/${props.SOURCE_FILE}.md5", force:true)
      }
      sh """
      cd ${props.SOURCE_DIR}
      if ! md5sum -c ${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5; then
        echo "Checksum check was failed. Redownloading ${props.SOURCE_FILE}"
        rm -f ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}
      fi
      """
      exists = fileExists "${props.SOURCE_DIR}/${props.SOURCE_FILE}"
      if (! exists) {
        withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
          s3Download(file:"${props.SOURCE_DIR}/${props.SOURCE_FILE}", bucket:"${props.S3_BUCKET}", path:"${props.HYBRIS_S3_SOURCEDIR}/${props.SOURCE_FILE}", force:true)
        }
      }
    }

      }
  } else {
  sh """
    shopt -s extglob
    if [ ! -d ${props.SOURCE_DIR} ]; then
      echo "Creating directory ${props.SOURCE_DIR}"
      mkdir -p ${props.SOURCE_DIR}
    fi
    if [ ! -f ${props.SOURCE_DIR}/${props.SOURCE_FILE} ]; then
      echo "Downloading ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}"
      wget --progress=dot:giga --quiet -O ${props.SOURCE_DIR}/${props.SOURCE_FILE} ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}
    else
      rm -f ${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5
      wget --progress=dot:giga --quiet -O ${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5 ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}.md5
      cd ${props.SOURCE_DIR}
      if ! md5sum -c ${props.SOURCE_DIR}/${props.SOURCE_FILE}.md5; then
        echo "Checksum check was failed. Redownloading ${props.SOURCE_FILE}"
        rm -f ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}
        wget --progress=dot:giga --quiet -O ${props.SOURCE_DIR}/${props.SOURCE_FILE} ${props.HYBRIS_SOURCES_URL}/${props.SOURCE_FILE}
      fi
    fi
  """
  }
  // Check if hybris directory exists, if not - creates directory and unzips the hybris, if exists - removes the config directory
  // sync the hybris custom modules, ci directory and customize directory to the hybris roo dir
  sh """
      rm -rf ${HYBRIS_ROOT}/*
	  rm -rf ${HYBRIS_ROOT}/.git
      mkdir -p ${HYBRIS_ROOT}
      cd ${HYBRIS_ROOT}
      unzip -q ${props.SOURCE_DIR}/${props.SOURCE_FILE}
      rsync -a --delete ${WORKSPACE}/hybris/bin/custom/ ${HYBRIS_ROOT}/hybris/bin/custom/
      rsync -a --delete ${WORKSPACE}/ci ${HYBRIS_ROOT}
      rsync -a --delete ${WORKSPACE}/envprops ${HYBRIS_ROOT}
      rsync -a --delete ${WORKSPACE}/configtemplates ${HYBRIS_ROOT}
	  rsync -a --delete ${WORKSPACE}/.git ${HYBRIS_ROOT}
  """
}

// Prepare hybris for testing
def testPrepareHybris(pType=null, pRunAll=null){
  //set the default template to develop
  if (pType == null) {
    pType = "develop"
  }
  //create config from chosen template
  //copy customize folder to config
  //make ant customize to replace files from customize folder to $HYBRIS_BIN folder (new templates are here)
  //copy local.properties to config from needed env
  //run ant all
  //remove log dirs (if was not created from scratch we need a clean log folder)
  sh """
      cd ${CUSTOM_ANT_SCRIPT_DIR}
      . ./setantenv.sh
      ant createConfig -Dtemplate=${pType} -Denv=${ENVIRONMENT}
      ant customize
      . ./setantenv.sh
      if [ $pRunAll == null ]; then
        ant all
      fi
      if [ -d ${WORKSPACE}/log ]; then
        rm -rf ${WORKSPACE}/log
      fi
      if [ ! -d ${WORKSPACE}/log ]; then
        mkdir ${WORKSPACE}/log
      fi
  """
}

//cleanup workspace folder
def cleanWorkspace(){
  cleanWs()
}

//create zip artifact files
def createArtifacts(){
  sh """cd ${CUSTOM_ANT_SCRIPT_DIR}
    . ./setantenv.sh
    ant production -Dproduction.legacy.mode=false
  """
}

//moves all artifact files from hybris folder to workspace
def copyArtifactsToWorkspace(){
  sh """
    mv ${HYBRIS_ROOT}/hybris/temp/hybris/hybrisServer/*.zip ${WORKSPACE}
  """
}

//upload the artifact files to artifactory storage (AS)
def uploadArtifacts(pBranch=null, pBuildid=null) {
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //if not mentioned the branch and job id - it will get that params from env variables
  if (pBranch == null) {
    pBranch = "${env.GIT_BRANCH}"
  }
  if (pBuildid == null) {
    pBuildid = "${env.BUILD_ID}"
  }
  //Prepare file with git commit hash
    writeFile file: 'git_commit.sha', text: "${env.GIT_COMMIT}"
  //Prepare file with current build ID
    writeFile file: 'build.properties', text: "BUILD_ID=${pBuildid}"
  //upload artifacts according to AS type
  //supported are: nexus, ftp (also can be accessed by http), local (on the same server), s3 (via s3 pipeline plugin)
  if ("${props.ARTIFACT_STORAGE}" == 'nexus') {
      nexusArtifactUploader artifacts: [[artifactId: 'build', classifier: '', file: 'build.properties', type: 'properties'],[artifactId: 'git_commit', classifier: '', file: 'git_commit.sha', type: 'sha'],[artifactId: 'hybrisServer-AllExtensions', classifier: '', file: 'hybrisServer-AllExtensions.zip', type: 'zip'], [artifactId: 'hybrisServer-Config', classifier: '', file: 'hybrisServer-Config.zip', type: 'zip'], [artifactId: 'hybrisServer-Licence', classifier: '', file: 'hybrisServer-Licence.zip', type: 'zip'], [artifactId: 'hybrisServer-Platform', classifier: '', file: 'hybrisServer-Platform.zip', type: 'zip']], credentialsId: "${USER_CREDS}", groupId: 'hybris', nexusUrl: "${props.URL}", nexusVersion: "${props.NEXUS_VERSION}", protocol: "${props.PROTOCOL}", repository: pBranch, version: "${pBranch}_${pBuildid}"
  } else if ("${props.ARTIFACT_STORAGE}" == 'ftp') {
      sh """
        ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} mkdir -p ${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
        rsync -apv ${WORKSPACE}/*.zip ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
        rsync -apv ${WORKSPACE}/git_commit.sha ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
        rsync -apv ${WORKSPACE}/build.properties ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
        ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} rm -rf ${props.ROOT_FOLDER}/${pBranch}/latest
        ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} ln -s ${props.ROOT_FOLDER}/${pBranch}/${pBuildid} ${props.ROOT_FOLDER}/${pBranch}/latest
      """
  }
  else if ("${props.ARTIFACT_STORAGE}" == 'local') {
    sh """
      mkdir -p ${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
      rsync -av ${WORKSPACE}/*.zip ${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
      rsync -av ${WORKSPACE}/git_commit.sha ${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
      rsync -av ${WORKSPACE}/build.properties ${props.ROOT_FOLDER}/${pBranch}/${pBuildid}
      rm -rf ${props.ROOT_FOLDER}/${pBranch}/latest
      ln -s ${props.ROOT_FOLDER}/${pBranch}/${pBuildid} ${props.ROOT_FOLDER}/${pBranch}/latest
    """
  }
  else if ("${props.ARTIFACT_STORAGE}" == 's3') {
   withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
      s3Upload(bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pBuildid}/", includePathPattern:'*.zip', workingDir:"${WORKSPACE}")
      s3Upload(bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pBuildid}/", file:"${WORKSPACE}/git_commit.sha")
      s3Upload(bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pBuildid}/", file:"${WORKSPACE}/build.properties")
      }
  }
}

//upload the artifact files to artifactory storage (AS)
def uploadDBArtifacts(String folderName) {
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //upload artifacts according to AS type
  //supported are: nexus, ftp (also can be accessed by http), local (on the same server)
  if ("${props.DUMP_ARTIFACT_STORAGE}" == 'nexus') {
      nexusArtifactUploader artifacts: [[artifactId: 'medias', classifier: '', file: 'medias.zip', type: 'zip'],[artifactId: 'DB_dump', classifier: '', file: 'DB_dump.dbf', type: 'dbf']], credentialsId: "${USER_CREDS}", groupId: "hybris", nexusUrl: "${props.URL}", nexusVersion: "${props.NEXUS_VERSION}", protocol: "${props.PROTOCOL}", repository: DB_dump, version: folderName
  } else if ("${props.DUMP_ARTIFACT_STORAGE}" == 'http') {
      sh """
        ssh -o StrictHostKeyChecking=no ${props.DUMP_ARTIFACT_USER}@${props.DUMP_SERVER_NAME} mkdir -p ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
        rsync -apv ${WORKSPACE}/db_dump.zip ${props.DUMP_ARTIFACT_USER}@${props.DUMP_SERVER_NAME}:${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
        rsync -apv ${WORKSPACE}/medias.zip ${props.DUMP_ARTIFACT_USER}@${props.DUMP_SERVER_NAME}:${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
        ssh -o StrictHostKeyChecking=no ${props.DUMP_ARTIFACT_USER}@${props.DUMP_SERVER_NAME} rm -rf ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/latest
        ssh -o StrictHostKeyChecking=no ${props.DUMP_ARTIFACT_USER}@${props.DUMP_SERVER_NAME} ln -s ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName} ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/latest
      """
  }
  else if ("${props.DUMP_ARTIFACT_STORAGE}" == 'local') {
    sh """
      mkdir -p ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
      rsync -av ${WORKSPACE}/db_dump.zip ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
      rsync -av ${WORKSPACE}/medias.zip ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}
      rm -rf ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/latest
      ln -s ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName} ${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/latest
    """
  }
  else if ("${props.DUMP_ARTIFACT_STORAGE}" == 's3') {
   withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
      s3Upload(bucket:"${props.S3_BUCKET}", path:"${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}/", file:"${WORKSPACE}/db_dump.zip")
      s3Upload(bucket:"${props.S3_BUCKET}", path:"${props.DUMP_ROOT_FOLDER}/${ENVIRONMENT}/${folderName}/", file:"${WORKSPACE}/medias.zip")
    }
  }
}

//download artifacts on hybris server if it is using jenkins agent (not remote)
def downloadArtifacts(pBranch, pBuildid){
  //Loads the global.properties file to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //prepare variable for relative path dependent of AS type
  def aPath = ""
    if ("${props.ARTIFACT_STORAGE}" == 's3') {
      //Download files
     withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
        def hybrisFiles = s3FindFiles(bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pBuildid}", glob:'hybris*.zip')
        for (files in hybrisFiles) {
          s3Download(file:"${WORKSPACE}/${files.name}", bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pBuildid}", force:true)
        }
      }
    }
    else if ("${props.ARTIFACT_STORAGE}" == 'nexus') {
      aPath = ""
      //Download files
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-AllExtensions.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-AllExtensions.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Config.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Config.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Licence.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Licence.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Platform.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Platform.zip", userName: '')])
    }
    else if ("${props.ARTIFACT_STORAGE}" == 'ftp' || "${props.ARTIFACT_STORAGE}" == 'local') {
      aPath = "${pBranch}/${pBuildid}"
      //Download files
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-AllExtensions.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-AllExtensions.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Config.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Config.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Licence.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Licence.zip", userName: '')])
      fileOperations([fileDownloadOperation(password: '', targetFileName: 'hybrisServer-Platform.zip', targetLocation: "${WORKSPACE}", url: "${props.ARTIFACTORY_URL}/${aPath}/hybrisServer-Platform.zip", userName: '')])

    }

   //unzip files
  fileOperations([fileUnZipOperation(filePath: "${WORKSPACE}/hybrisServer-AllExtensions.zip", targetLocation: "${HYBRIS_ROOT}")])
  fileOperations([fileUnZipOperation(filePath: "${WORKSPACE}/hybrisServer-Config.zip", targetLocation: "${HYBRIS_ROOT}")])
  fileOperations([fileUnZipOperation(filePath: "${WORKSPACE}/hybrisServer-Licence.zip", targetLocation: "${HYBRIS_ROOT}")])
  fileOperations([fileUnZipOperation(filePath: "${WORKSPACE}/hybrisServer-Platform.zip", targetLocation: "${HYBRIS_ROOT}")])
  //delete zip files
  fileOperations([fileDeleteOperation(excludes: '', includes: '*.zip')])
//should be uncommented to use h-up, but need to fix issues
/*  sh """
    h-up --deploy-release ${aPath} --initialize
  """*/
}

//to send email after artifact creation with changes
def sendArtifactsCreatedEmail(aBody=null){
  pRecepient = props.EMAIL_DEVOPS_GROUP
  if (envprops.EMAIL_SEND_TO_QA.toLowerCase() == "true") {
    pRecepient += ",${props.EMAIL_QA_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_DEV.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_DEV_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_PROJECT.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_PROJECT_GROUP}"
  }
  pSubj = """[Artifact] The Artifact ${BUILD_ID} was created for branch ${GIT_BRANCH}"""

  pBody = templateModule.artifactCreatedTemplate()
  pBody += aBody
  pBody += "\n</BODY>"

  emailext mimeType: 'text/html', attachmentsPattern: '*.xlsx', body: pBody, recipientProviders: [], subject: pSubj, to: pRecepient
}

//send email that rebuild is going to start
def sendDeployStartEmail(){
  pRecepient = ""
  if (env.USER_MAIL) {
    pRecepient = env.USER_MAIL
  }
  if (envprops.EMAIL_SEND_TO_QA.toLowerCase() == "true") {
    pRecepient += ",${props.EMAIL_QA_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_DEV.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_DEV_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_PROJECT.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_PROJECT_GROUP}"
  }

  pSubj = """[DEPLOY] The ${env.ENVIRONMENT} environment is going to be rebuilt"""
  emailext mimeType: 'text/html', attachmentsPattern: '*.xlsx', body: templateModule.deployStartTemplate(), recipientProviders: [], subject: pSubj, to: pRecepient
}

//send email that there was no new artifact
def sendNoNewArtifactEmail(){
  pRecepient = ""
  if (env.USER_MAIL) {
    pRecepient = env.USER_MAIL
  }
  if (envprops.EMAIL_SEND_TO_QA.toLowerCase() == "true") {
    pRecepient += ",${props.EMAIL_QA_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_DEV.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_DEV_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_PROJECT.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_PROJECT_GROUP}"
  }
  pBody = """
    The ${env.ENVIRONMENT} environment was not rebuild using artifacts from ${env.GIT_BRANCH}/${env.ARTIFACT_ID} with artifact ID ${buildprops.BUILD_ID}.

    You can check build logs using the link ${BUILD_URL}.
    """
  pSubj = """[No Changes] The ${env.ENVIRONMENT} environment was not rebuilt cause there was no new artifact"""
  emailext attachmentsPattern: '*.xlsx', body: pBody, recipientProviders: [], subject: pSubj, to: pRecepient
}

//send email about multinode env prepared
def sendEmail(pStatus=null, pMNTestResult=null, pMNTestUrl=null) {
  if (pStatus == null){
    pStatus = "SUCCESS"
  }
  pRecepient = ""
  if (env.USER_MAIL) {
    pRecepient = env.USER_MAIL
  }
  if (envprops.EMAIL_SEND_TO_QA.toLowerCase() == "true") {
    pRecepient += ",${props.EMAIL_QA_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_DEV.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_DEV_GROUP}"
  }
  if (envprops.EMAIL_SEND_TO_PROJECT.toLowerCase() == "true") {
    pRecepient += ",cc:${props.EMAIL_PROJECT_GROUP}"
  }
  pSubj = ""
  echo pMNTestResult
  echo pMNTestUrl
  if (pMNTestResult == "SUCCESS" || pMNTestResult == null) {
    pSubj = """[${pStatus}] The ${env.ENVIRONMENT} environment was rebuild"""
  } else {
    pSubj = """[${pStatus}] The ${env.ENVIRONMENT} environment was rebuild with Manual Tests failure"""
  }

    emailext mimeType: 'text/html', attachmentsPattern: '${ENVIRONMENT}_release_notes.*', body: templateModule.deployFinishedTemplate(pMNTestResult,pMNTestUrl), recipientProviders: [], subject: pSubj, to: pRecepient
}

//send email about FV env state:preparing, started, finished, failed, qa_rejected
def sendEmailFV(pAction, pRecepient) {
    if (["started","finished"].contains(pAction) && env.FV_QA_REVIEW == "true"){
      if (envprops.EMAIL_SEND_TO_QA.toLowerCase() == "true") {
        pRecepient += ",cc:${props.EMAIL_QA_GROUP}"
      }
      if (envprops.EMAIL_SEND_TO_DEV.toLowerCase() == "true") {
        pRecepient += ",cc:${props.EMAIL_DEV_GROUP}"
      }
      if (envprops.EMAIL_SEND_TO_PROJECT.toLowerCase() == "true") {
        pRecepient += ",cc:${props.EMAIL_PROJECT_GROUP}"
      }
    }
    SERVER_NAME = sh (script: props.HOSTNAME_CHECK, returnStdout: true).trim()
    SITE_LINKS=""
    for (sl in env.SITE_URLS.split("\n")){
      SITE_LINKS += "https://${SERVER_NAME}:9002${sl}\n"
      SITE_LINKS += "https://${SERVER_NAME.split("\\.")[0]}-console.server.by${sl}\n"
    }

    emailext mimeType: 'text/html', body: templateModule.fvTemplateBody(pAction), recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: templateModule.fvTemplateSubj(pAction), to: pRecepient
}

def inputWait(pWaitTime, pWaitUnit, pMessage){
  echo "Timeout for ${pWaitTime} ${pWaitUnit}"
  timeout(time: pWaitTime, unit: pWaitUnit) {
    input message: pMessage, ok: 'Accept'
  }
}

//make ant deploy to prepare tomcat for server start
def prepareTomcat(){
  sh """cd ${CUSTOM_ANT_SCRIPT_DIR}
    . ./setantenv.sh
    ant deploy
  """
}


def downloadRemoteArtifacts(String pSshagent, String serverName, String pUser, pBranch, pBuildid){
  //Loads the global.properties fole to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  //prepare variable for relative path dependent of AS type
  def aPath = ""
  if ("${props.ARTIFACT_STORAGE}" == 'nexus') {
    aPath = ""
  } else if ("${props.ARTIFACT_STORAGE}" == 'ftp') {
    aPath = "${pBranch}/${pBuildid}"
  } else if ("${props.ARTIFACT_STORAGE}" == 'local') {
    aPath = "${pBranch}/${pBuildid}"
  }
  //Download files and prepare hybris
  sshagent([pSshagent]) {
    if (props.NOHUP == "false") {
      sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --deploy-release ${aPath}'"
      """
    } else {
      echo "TODO"
    }

  }
}

//start hybris server on remote server. Need to create user/password credentials on jenkins with sshagent ID
def startRemoteServer(String pSshagent, String serverName, String pUser) {
  sshagent([pSshagent]) {
    if (props.NOHUP == "false") {
      sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'systemctl start hybrisd.service'"
      """
    } else {
      sh """
        cd ${props.HYBRIS_ROOT}/hybris/bin/platform
        ./hybrisserver.manage.sh start
      """
    }
  }
}
def startRemoteServerParallel(String pSshagent, String serverName, String pUser) {
  return{
  sshagent([pSshagent]) {
    sh """
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'systemctl start hybrisd.service'"
    """
  }
  }
}

//stop hybris server on remote server. Need to create user/password credentials on jenkins with sshagent ID
def stopRemoteServer(String pSshagent, String serverName, String pUser) {
	  sshagent([pSshagent]) {
      if (props.NOHUP == "false") {
        sh """
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'systemctl stop hybrisd.service'"
        """
      } else {
        sh """
          cd ${props.HYBRIS_ROOT}/hybris/bin/platform
          ./hybrisserver.manage.sh stop
        """
      }
	  }
}
def stopRemoteServerParallel(String pSshagent, String serverName, String pUser) {
  return {
	  sshagent([pSshagent]) {
		sh """
		  ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'systemctl stop hybrisd.service'"
		"""
	  }
  }
}

//update hybris codebase on remote server. Need to create user/password credentials on jenkins with sshagent ID
def updateRemoteServer(String pSshagent, String artifactsUrl, String serverName, String pUser, serverType=null){
	  if (serverType){
		sshagent([pSshagent]) {
      if (props.HONUP == "false"){
        sh """
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
    			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --deploy-release ${artifactsUrl} --env-type ${ENVIRONMENT} --server-type ${serverType}'"
  		  """
      } else {
        sh """
          rm -rf /tmp/*.impex
    			rm -rf ${props.HYBRIS_ROOT}/*
  		  """
        withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
          s3Download(file:"${artifactsUrl}/", bucket:"${props.S3_BUCKET}", path:"${props.SOURCE_DIR}/", force:true)
        }
        sh """
          cd ${props.HYBRIS_ROOT}
          unzip ${props.SOURCE_DIR}/hybrisServer*.zip
          rm -rf ${props.SOURCE_DIR}/hybrisServer*.zip
          cd ${CUSTOM_ANT_SCRIPT_DIR}
          . ./setantenv.sh
          ant deploy -Denv=${ENVIRONMENT} -Dtype=${serverType}
        """
      }

		}
	  } else {
		sshagent([pSshagent]) {
      if (props.HONUP == "false"){
        sh """
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
    			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --deploy-release ${artifactsUrl} --env-type ${ENVIRONMENT}'"
  		  """
      } else {
        sh """
          rm -rf /tmp/*.impex
    			rm -rf ${props.HYBRIS_ROOT}/*
  		  """
        withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
          s3Download(file:"${artifactsUrl}/", bucket:"${props.S3_BUCKET}", path:"${props.SOURCE_DIR}/", force:true)
        }
        sh """
          cd ${props.HYBRIS_ROOT}
          unzip ${props.SOURCE_DIR}/hybrisServer*.zip
          rm -rf ${props.SOURCE_DIR}/hybrisServer*.zip
          cd ${CUSTOM_ANT_SCRIPT_DIR}
          . ./setantenv.sh
          ant deploy -Denv=${ENVIRONMENT}
        """
      }

		}
	  }
}
def updateRemoteServerParallel(String pSshagent, String artifactsUrl, String serverName, String pUser, serverType=null){
  return {
	  if (serverType){
		sshagent([pSshagent]) {
		  sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
  			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --deploy-release ${artifactsUrl} --env-type ${ENVIRONMENT} --server-type ${serverType}'"
		  """
		}
	  } else {
		sshagent([pSshagent]) {
		  sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
  			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --deploy-release ${artifactsUrl} --env-type ${ENVIRONMENT}'"
		  """
		}
	  }
  }
}

def addPropertiesRemoteServerParallel(String pSshagent, String serverName, String pUser, String propertyToAdd){
  return {
    sshagent([pSshagent]) {
      sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "echo ${propertyToAdd} >> ${HYBRIS_ROOT}/hybris/config/local.properties"
      """
    }
  }
}

//update hybris codebase on remote server. Need to create user/password credentials on jenkins with sshagent ID
def initOnRemoteServer(String pSshagent, String serverName, String pUser, serverType=null){
  //Loads the global.properties file to get the global environment variables
  props = readProperties file: "ci/conf/global.properties"
  if (serverType){
    sshagent([pSshagent]) {
      sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --initialize --env-type ${ENVIRONMENT} --server-type ${serverType}'"
      """
    }
  } else {
    sshagent([pSshagent]) {
      if (props.NOHUP == "false"){
        sh """
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --initialize --env-type ${ENVIRONMENT}'"
        """
      } else {
        sh """
          cd ${CUSTOM_ANT_SCRIPT_DIR}
          . ./setantenv.sh
          ant initialize
        """
      }

    }
  }
    step([$class: 'LogParserPublisher', failBuildOnError: true, parsingRulesPath: props.LOG_PARSER_RULES, useProjectRule: false])
  }


  //update hybris codebase on remote server. Need to create user/password credentials on jenkins with sshagent ID
	def incrementalUpdateOnRemoteServer(String pSshagent, String serverName, String pUser, serverType=null){
	  //Loads the global.properties file to get the global environment variables
	  props = readProperties file: "ci/conf/global.properties"
	  if (serverType){
		sshagent([pSshagent]) {
		  sh """
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --updatesystem --env-type ${ENVIRONMENT} --server-type ${serverType}'"
		  """
		}
	  } else {
		sshagent([pSshagent]) {
      if (props.NOHUP == "false"){
        sh """
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf /tmp/*.impex'"
  			ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'h-up --updatesystem --env-type ${ENVIRONMENT}'"
  		  """
      } else {
        sh """
          cd ${CUSTOM_ANT_SCRIPT_DIR}
          . ./setantenv.sh
          ant incrementalupdate -Denv=${ENVIRONMENT}
        """
      }

		}
	  }
		step([$class: 'LogParserPublisher', failBuildOnError: true, parsingRulesPath: props.LOG_PARSER_RULES, useProjectRule: false])
	}

  def jira_open_comment(GIT_SOURCE_BRANCH, GIT_MR_URL){
    //Loads the global.properties file to get the global environment variables
    props = readProperties file: "ci/conf/global.properties"
      try{
  /**
   * find jira issue
   * get it from GIT_SOURCE_BRANCH
   */
          def jira_issue = ("${GIT_SOURCE_BRANCH}" =~ ".*(${props.JIRA_PROJECT_CODE}-[0-9]*).*")[0][1]
          jiraComment body: "Pull request created ${GIT_MR_URL}", issueKey: "${jira_issue}"

      }catch(err){

          echo "Error was found during all jira comment about MR creation"

      }
  }
  def jira_merge_comment(GIT_SOURCE_BRANCH, GIT_TARGET_BRANCH, GIT_MR_URL){
    //Loads the global.properties file to get the global environment variables
    props = readProperties file: "ci/conf/global.properties"
      try{
          def jira_issue = ("${GIT_SOURCE_BRANCH}" =~ ".*(${props.JIRA_PROJECT_CODE}-[0-9]*).*")[0][1]
          echo jira_issue
          jiraComment body: "Merge request ${GIT_MR_URL}\nbranch ${GIT_SOURCE_BRANCH}\nwas merged to ${GIT_TARGET_BRANCH}", issueKey: "${jira_issue}"

      }catch(err){

          echo "Error was found during all jira comment about MR merge"

      }

  }

  def createMediasArtifact(){
    if (props.MEDIAS_STORAGE_TYPE == "local"){
      sshagent([envprops.SSHAGENT_ID]) {
        sh """
          mkdir -p ${WORKSPACE}/medias
  		ssh -o StrictHostKeyChecking=no ${props.DEPLOY_USER}@${envprops.MEDIAS_SERVER_NAME} "exit"
          rsync -a ${props.DEPLOY_USER}@${envprops.MEDIAS_SERVER_NAME}:${envprops.MEDIAS_FOLDER}/sys_master ${WORKSPACE}/medias/
          cd ${WORKSPACE}/medias
          zip -q -r medias.zip *
          mv medias.zip ${WORKSPACE}
          rm -rf ${WORKSPACE}/medias
          """
      }
    }
  }

  def importMediasArtifact(envName=null){
    if (!(envName)){
      envName = env.ENVIRONMENT
    }
    if (props.MEDIAS_STORAGE_TYPE == "local"){
      sshagent([envprops.SSHAGENT_ID]) {
        sh """
          mkdir -p ${WORKSPACE}/medias
          wget --progress=dot:giga -O ${WORKSPACE}/medias.zip ${props.DUMP_ARTIFACTORY_URL}/${envName}/${DUMP_ARTIFACT_ID}/medias.zip
          cd ${WORKSPACE}/medias
          unzip -q ${WORKSPACE}/medias.zip
  		    ssh -o StrictHostKeyChecking=no ${props.DEPLOY_USER}@${envprops.MEDIAS_SERVER_NAME} "exit"
          rsync -a --delete ${WORKSPACE}/medias/ ${props.DEPLOY_USER}@${envprops.MEDIAS_SERVER_NAME}:${envprops.MEDIAS_FOLDER}/
          rm -rf ${WORKSPACE}/medias
          rm -rf ${WORKSPACE}/medias.zip
        """
      }
    }
  }


  // updates static content on web servers
    def moveWebStaticContent(){
      if (props.ARTIFACT_STORAGE=="ftp"){
        sh """
          mkdir artifactTemp
          cd artifactTemp
          wget --progress=dot:giga --quiet -O hybrisServer-AllExtensions.zip ${props.ARTIFACTORY_URL}/${GIT_BRANCH}/${buildprops.BUILD_ID}/hybrisServer-AllExtensions.zip
          unzip -q hybrisServer-AllExtensions.zip
          rm -f hybrisServer-AllExtensions.zip
        """
        webServerList = envprops.WEB.split(",")
        for (serverName in webServerList){
          sshagent([envprops.WEB_SSHAGENT_ID]) {
            sh """
              ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "exit"
              ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "sudo su - -c 'mkdir -p ${envprops.STATIC_CONTENT_FOLDER} && chown -R ${envprops.WEB_USER}:${envprops.WEB_USER} ${envprops.STATIC_CONTENT_FOLDER}'"
            """
          }
          appList = envprops.APP_NAME_LIST.split(",")
          for (appName in appList){
            sshagent([envprops.WEB_SSHAGENT_ID]) {
              sh """
                ssh -t -t -o StrictHostKeyChecking=no ${envprops.WEB_USER}@${serverName} "mkdir -p ${envprops.STATIC_CONTENT_FOLDER}/${appName}"
                rsync -a ${WORKSPACE}/artifactTemp/hybris/bin/custom/${appName}/web/webroot/_ui ${envprops.WEB_USER}@${serverName}:${envprops.STATIC_CONTENT_FOLDER}/${appName}/
              """
            }
          }
        }
        sh "rm -rf ${WORKSPACE}/artifactTemp"

      }
    }

def killHybrisUpdateProcesses(String pSshagent, String serverName, String pUser, serverType=null){
  sshagent([pSshagent]) {
    sh """
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'pkill -SIGKILL -f ${props.HYBRIS_ROOT}'"
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'pkill -SIGKILL -f incrementalupdate'"
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'pkill -SIGKILL -f updatesystem'"
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'pkill -SIGKILL -f initialize'"
    """
  }
}

def propertyFileAction(String pSshagent, String serverName, String pUser, String pAction, String pFile, pProps=null) {
    toReturn = ""
	  sshagent([pSshagent]) {
      if (pAction == "update" && pProps != null){
        toReturn = sh(script:"""
    		  ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "if [ ! -f ${pFile}_backup ]; then cp ${pFile} ${pFile}_backup && echo 'File ${pFile} was backed up'; else echo 'Backup is already done'; fi"
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "echo '\n\n### Properties added by ${BUILD_URL}\n' >> ${pFile}"
          ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "echo -e '''${pProps.replace('$','\\\$').replace('"','\\"')}''' >> ${pFile}"
    		""", returnStdout: true).trim()
      }
      if (pAction == "revert"){
        toReturn = sh(script:"""
    		  ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "if [ -f ${pFile}_backup ]; then rm -f ${pFile} && mv ${pFile}_backup ${pFile} && echo 'File ${pFile} was restored'; else echo 'Nothing to restore'; fi"
    		""", returnStdout: true).trim()
      }
      if (pAction == "list"){
        toReturn = sh(script:"""
    		  ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "cat ${pFile}"
    		""", returnStdout: true).trim()
      }
	  }
  return toReturn
}

def copyRemoteLocalProperty(String pUser,String srvName) {
    sh "rsync -av ${pUser}@${srvName}:${props.HYBRIS_ROOT}/hybris/config/local.properties ${WORKSPACE}"
}


return this
