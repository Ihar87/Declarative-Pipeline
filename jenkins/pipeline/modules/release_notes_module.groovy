// read build properties from artifact
def readBuildProperties(pArtifactdID=null, pBranch=null){
  props = readProperties file: "ci/conf/global.properties"
  if (pArtifactdID == null) {
    pArtifactdID = env.ARTIFACT_ID
  }
  if (pBranch == null) {
    pBranch = env.GIT_BRANCH
  }
  if ("${props.ARTIFACT_STORAGE}" == 's3') {
    withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
      s3Download(file:"build.properties", bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pArtifactdID}/build.properties", force:true)
      }
    }
  else {
    sh """
    wget -O build.properties ${props.ARTIFACTORY_URL}/${pBranch}/${pArtifactdID}/build.properties
    """
    }
  buildprops = readProperties file: "build.properties"
  return buildprops
}

// read commit SHA from artifactory
def readArtifactSHA(pArtifactdID=null, pBranch=null){
  props = readProperties file: "ci/conf/global.properties"
  if (pArtifactdID == null) {
    pArtifactdID = env.ARTIFACT_ID
  }
  if (pBranch == null) {
    pBranch = env.GIT_BRANCH
  }
  if ("${props.ARTIFACT_STORAGE}" == 's3') {
    withAWS(region:"${props.S3_REGION}",credentials:"AWS_CREDENTIALS") {
      s3Download(file:"git_commit.sha", bucket:"${props.S3_BUCKET}", path:"${props.ROOT_FOLDER}/${pBranch}/${pArtifactdID}/git_commit.sha", force:true)
      }
    }
  else {
    sh """
    wget -O git_commit.sha ${props.ARTIFACTORY_URL}/${pBranch}/${pArtifactdID}/git_commit.sha
    """
    }
  pCommitSHA = readFile "git_commit.sha"
  return pCommitSHA.trim()
}

// read commit SHA from file on jenkins
def readCurrentSHA(){
  pCommitSHA = ""
  def exists = fileExists "${props.GIT_COMMIT_FOLDER}/${env.ENVIRONMENT}.sha"
  if (exists){
    pCommitSHA = readFile "${props.GIT_COMMIT_FOLDER}/${env.ENVIRONMENT}.sha"
  } else {
    pCommitSHA = sh (script: """git rev-list --max-parents=0 HEAD""", returnStdout: true).trim()
  }
  return pCommitSHA.trim()
}

// write commit SHA to file on jenkins
def writeSHAtoJenkins(String pCommitSHA){
  writeFile file: "${env.ENVIRONMENT}.sha", text: pCommitSHA
  sh """
    rm -f ${props.GIT_COMMIT_FOLDER}/${env.ENVIRONMENT}.sha
    mv ${env.ENVIRONMENT}.sha ${props.GIT_COMMIT_FOLDER}
  """
}

// generate release notes and/or add them to confluence
def generateReleaseNotes(pStartSHA = null, pEndSHA = null){
  if (pStartSHA == null){
    pStartSHA = readCurrentSHA()
  }
  if (pEndSHA == null){
    pEndSHA = readArtifactSHA()
  }
  withCredentials([usernamePassword(credentialsId: props.RN_JENKINS_CONF_CREDS_ID, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
    try{
      if (envprops.RN_ADD_TO_CONFLUENCE){
        shOut = sh(script:"""
          python ci/jenkins/git_html_report.py --start-sha ${pStartSHA} --end-sha ${pEndSHA} --env-name ${env.ENVIRONMENT} --jira-url ${props.JIRA_URL} --jira-user ${USERNAME} --jira-password ${USERPASSWORD} --filename release_notes.html --project-code ${props.JIRA_PROJECT_CODE} --artifact-id ${buildprops.BUILD_ID} --git-branch ${GIT_BRANCH} --add-to-confluence --conf-project-space-key ${props.RN_PROJECT_SPACE_KEY} --conf-root_page_id ${envprops.RN_ROOT_PAGE_ID} --conf-url ${props.RN_CONFLUENCE_URL} --conf-server-name ${props.RN_CONF_SERVER_NAME}
        """, returnStdout: true).trim()
        echo shOut
        for (i in shOut.split('\n')){
          if (i.startsWith('id: ')){
              def ret = (i =~ /^id: ([\d]+)$/)
              if (ret.matches()) {
                kb_rn_page_id = ret[0][1]
              }
          }
        }
      } else{
        sh """
          python ci/jenkins/git_html_report.py --start-sha ${pStartSHA} --end-sha ${pEndSHA} --env-name ${env.ENVIRONMENT} --jira-url ${props.JIRA_URL} --jira-user ${USERNAME} --jira-password ${USERPASSWORD} --filename release_notes.html --project-code ${props.JIRA_PROJECT_CODE} --artifact-id ${buildprops.BUILD_ID} --git-branch ${GIT_BRANCH}
        """
      }

    }catch(err2) {
      echo "Failed to create release notes"
    }

  }
}

return this
