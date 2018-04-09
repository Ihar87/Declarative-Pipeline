/*
properties([gitLabConnection(''),
            parameters([choice(choices: ['fqa1', 'fqa2', 'uat1', 'uat2', 'perf', 'prod'], description: 'Environment name', name: 'ENVIRONMENT'),
                        booleanParam(defaultValue: true, description: 'to send release notes by email', name: 'EMAIL_RN'),
                        booleanParam(defaultValue: true, description: 'to add release notes to confluence', name: 'PUBLISH_RN'),
                        string(defaultValue: '', description: '''email addresses to send Release Notes
                                                                  to this email list delimited by comma will be sent email
                                                                  cc:EMAIL to include to CC''', name: 'EMAIL'),
                        string(defaultValue: 'develop', description: 'Git branch contained artifact to get start SHA', name: 'START_GIT_BRANCH'),
                        string(defaultValue: '1115', description: 'Start artifact id', name: 'START_ARTIFACT_ID'),
                        string(defaultValue: 'develop', description: 'Git branch contained artifact to get end SHA', name: 'END_GIT_BRANCH'),
                        string(defaultValue: 'latest', description: 'End artifact id', name: 'END_ARTIFACT_ID'),
                        string(defaultValue: '', description: 'Custom page ID to create subpage', name: 'CONF_ROOT_PAGE'),
                        booleanParam(defaultValue: false, description: 'to include only type and jira ticket reference', name: 'DO_NOT_INCLUDE_COMMITS'),
                        string(defaultValue: '', description: '''Custom page name
                                                                  default is:
                                                                  Release Notes: ENV_NAME END_GIT_BRANCH_NAME END_ARTIFACT_ID''', name: 'CONFLUENCE_PAGE_NAME'),
                        string(defaultValue: '', description: 'do not change!', name: 'GIT_URL'),
                        string(defaultValue: 'develop', description: 'do not change!', name: 'GIT_BRANCH'),
                        credentials(credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: 'gitlab', description: '', name: 'BB_CREDENTIALS', required: false)]),
            pipelineTriggers([])])

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
  //Loads the release notes module
  rnModule = load "ci/jenkins/pipeline/modules/release_notes_module.groovy"

  currentBuild.description = "${ENVIRONMENT}"
  kb_rn_page_id = ""
  start_sha = rnModule.readArtifactSHA(env.START_ARTIFACT_ID, env.START_GIT_BRANCH)
  end_sha = rnModule.readArtifactSHA(env.END_ARTIFACT_ID, env.END_GIT_BRANCH)
  confRootPageId = ""
  if (CONF_ROOT_PAGE == ""){
    confRootPageId = envprops.RN_ROOT_PAGE_ID
  } else {
    confRootPageId = env.CONF_ROOT_PAGE
  }
  shOut = ""
  confAdditionalParams = ""
  if (env.DO_NOT_INCLUDE_COMMITS == "true"){
    confAdditionalParams +=" --skip-ticket-commits"
  }
  if (env.CONFLUENCE_PAGE_NAME != ""){
    confAdditionalParams +=" --conf-custom-page-name '${CONFLUENCE_PAGE_NAME}' "
  }

  buildprops = ""
  try{
    buildprops = rnModule.readBuildProperties(env.END_ARTIFACT_ID, env.END_GIT_BRANCH)
  } catch(err){
    echo "Error during build props read"
  }
  stage("Create Release notes"){
    withCredentials([usernamePassword(credentialsId: props.RN_JENKINS_CONF_CREDS_ID, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      if (env.PUBLISH_RN == "true"){
        shOut = sh(script:"""
          python ci/jenkins/git_html_report.py --start-sha ${start_sha} --end-sha ${end_sha} --env-name ${env.ENVIRONMENT} --jira-url ${props.JIRA_URL} --jira-user ${USERNAME} --jira-password ${USERPASSWORD} --filename release_notes.html --project-code ${props.JIRA_PROJECT_CODE} --artifact-id ${buildprops.BUILD_ID} --git-branch ${GIT_BRANCH} --add-to-confluence --conf-project-space-key ${props.RN_PROJECT_SPACE_KEY} --conf-root_page_id ${confRootPageId} --conf-url ${props.RN_CONFLUENCE_URL} --conf-server-name ${props.RN_CONF_SERVER_NAME} ${confAdditionalParams}
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
      } else {
          sh """
            python ci/jenkins/git_html_report.py --start-sha ${start_sha} --end-sha ${end_sha} --env-name ${env.ENVIRONMENT} --jira-url ${props.JIRA_URL} --jira-user ${USERNAME} --jira-password ${USERPASSWORD} --filename release_notes.html --project-code ${props.JIRA_PROJECT_CODE} --artifact-id ${buildprops.BUILD_ID} --git-branch ${GIT_BRANCH}
          """
        }
    }
  }



  stage("Send Email"){
    pRecepient = getRec()
    if (env.EMAIL != ""){
      pRecepient +=","
      pRecepient += env.EMAIL
    }
    if (env.EMAIL_RN == "true" && env.PUBLISH_RN == "false"){
      emailext attachmentsPattern: '${ENVIRONMENT}_release_notes.*', body: "Release Notes", recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} Release notes", to: pRecepient
    }
    if (env.EMAIL_RN == "false" && env.PUBLISH_RN == "true"){
      pBody = "Release Notes\n Page was created: "
      if (kb_rn_page_id == ""){
        pBody += "${props.RN_CONFLUENCE_URL}/display/${props.RN_PROJECT_SPACE_KEY}/${ENVIRONMENT.toUpperCase()}"
      } else {
        pBody += "${props.RN_CONFLUENCE_URL}/pages/viewpage.action?pageId=${kb_rn_page_id}"
      }
      emailext body: pBody, recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} Release notes", to: pRecepient
    }
    if (env.EMAIL_RN == "true" && env.PUBLISH_RN == "true") {
      pBody = "Release Notes\n Page was created: "
      if (kb_rn_page_id == ""){
        pBody += "${props.RN_CONFLUENCE_URL}/display/${props.RN_PROJECT_SPACE_KEY}/${ENVIRONMENT.toUpperCase()}"
      } else {
        pBody += "${props.RN_CONFLUENCE_URL}/pages/viewpage.action?pageId=${kb_rn_page_id}"
      }
      emailext attachmentsPattern: '${ENVIRONMENT}_release_notes.*', body: pBody, recipientProviders: [], subject: "${ENVIRONMENT.toUpperCase()} Release notes", to: pRecepient
    }
  }

}
