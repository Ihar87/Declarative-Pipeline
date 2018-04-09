//This module is for email templates
// Until bug is resolved or workarround is provided
//https://issues.jenkins-ci.org/browse/JENKINS-47017
//https://issues.jenkins-ci.org/browse/JENKINS-43403

header = """

<STYLE>
BODY {
  font-family:Verdana,Helvetica,sans serif;
  font-size:12px;
  color:black;
}
TABLE, TD, TH, P {
  font-family:Verdana,Helvetica,sans serif;
  font-size:12px;
  color:black;
  border:1px solid black;
  border-collapse: collapse;
}

th, td {
    padding: 5px;
}

h1 { color:black; }
h2 { color:black; }
h3 { color:black; }
TD.bg1 { color:white; background-color:#0000C0; font-size:120% }
TD.bg2 { color:white; background-color:#4040FF; font-size:110% }
TD.bg3 { color:white; background-color:#8080FF; }
TD.test_passed { color:blue; }
TD.test_failed { color:red; }
TD.console { font-family:Courier New; }
</STYLE>
<BODY>

"""
//to create HTML code with table contains site URLs
// Example:
//Cluster:
//https://www.cl.com
//nodes:
//https://n1:9002/hybrisaccelerator
//https://n2:9002/hybrisaccelerator
def createUrlTable(textWithUrls){
  pMap = [:]
  pLastKey = ""
  pList = textWithUrls.split("\n")
  for (pNote in pList){
      if (pNote!=""){
          if (!pNote.startsWith('http')){
              pMap[pNote] = []
              pLastKey = pNote
          } else {
              pMap[pLastKey].add(pNote)
          }
      }
  }
  pCheck = 0
  pBody = ""
  pBody += "<TABLE>\n"
  for (pItem in pMap){
      pCheck = 0
      keyLength = pItem.value.size()
      if (keyLength == 1){
          pBody += "<TR>\n<TH>${pItem.key}</TH>\n<TD>${pItem.value[0]}</TD>\n</TR>\n"
      } else {
          for (pLink in pItem.value){
              if (pCheck == 0){
                  pBody += "<TR>\n<TH rowspan='${keyLength}'>${pItem.key}</TH>\n<TD>${pLink}</TD>\n</TR>\n"
              } else {
                  pBody += "<TR>\n<TD>${pLink}</TD>\n</TR>\n"
              }
              pCheck += 1
          }
      }
  }
  pBody += "</TABLE>\n</BODY>"
  return pBody
}


//email about artifact creation
def artifactCreatedTemplate(){
  pBody = header
  pBody += "<h2>Artifact was created</h2>\n"
  pBody += """<TABLE><TR><TH>ID</TH><TD>${BUILD_ID}</TD></TR><TR><TH>Branch name</TH><TD>${GIT_BRANCH}</TD></TR><TR><TH>URL</TH><TD>${props.ARTIFACTORY_URL}/${GIT_BRANCH}/${BUILD_ID}</TD></TR></TABLE><BR/>\n"""
  pBody += """<h3>Changes for this Artifact</h3>"""
  return pBody
}

//template for buildkit emails
def buildKitTemplate(){
  pBody = header
  pBody += "<h2>The buildkit for ${GIT_BRANCH} was updated.</h2>\n"
  pBody += "<h3>BuildKit common URL: ${props.BUILDKIT_URL}</h3>"
  pBody += "Artifact info:"
  pBody += """<TABLE><TR><TH>ID</TH><TD>${BUILD_ID}</TD></TR><TR><TH>Branch name</TH><TD>${GIT_BRANCH}</TD></TR><TR><TH>URL</TH><TD>${props.ARTIFACTORY_URL}/${GIT_BRANCH}/${BUILD_ID}</TD></TR></TABLE><BR/>\n"""
  pBody += "</BODY>"
  return pBody
}

//template for Feature Verification emails
def fvTemplateBody(fvStatus){
  pBody = header
  if (fvStatus == "preparing"){
      pBody += "<h3>The environment for Feture Verification is going to be started on ${SERVER_NAME} server.</h3>"
      pBody += "<b>Deploy parameters:</b><BR/>"
      pBody += "<b>DB preparation:</b> ${DB_PREPARE_METHOD}<BR/>"
      if (env.DB_PREPARE_METHOD == "update"){
        pBody += "<b>Dump env:</b> ${ENV_DUMP}<BR/>"
        pBody += "<b>Dump ID:</b> ${DUMP_ARTIFACT_ID}<BR/>"
      }
      pBody += "<h3>To check deployment status please follow the link: ${BUILD_URL}.</h3>\n</BODY>"
      return pBody
  } else if (fvStatus == "started"){
      pBody += "<h3>The environment for Feature Verification was started.</h3>"
      pBody += "<h3>Deploy parameters:</h3>"
      pBody += "<b>DB preparation:</b> ${DB_PREPARE_METHOD}<BR/>"
      if (env.DB_PREPARE_METHOD == "update"){
        pBody += "<b>Dump env:</b> ${ENV_DUMP}<BR/>"
        pBody += "<b>Dump ID:</b> ${DUMP_ARTIFACT_ID}<BR/>"
      }
      pBody += "<BR/><TABLE><TR><TD>https://${SERVER_NAME.split("\\.")[0]}-www.server.by</TD></TR><TR><TD>https://${SERVER_NAME.split("\\.")[0]}-api.server.by/api</TD></TR>\n"
      for (st in SITE_LINKS.split("\n")){
        pBody += "<TR><TD>${st}</TD></TR>\n"
      }
      pBody +="</TABLE><BR/></BR>\n"
      pBody += "<b>After verification please follow the link ${BUILD_URL}input and choose Approve or Reject for build ${BUILD_ID}.</b><BR/>"

      if (env.FV_QA_REVIEW == "true"){
        pBody += "<BR/><b>Feature Verification environment will be automatically rejected in ${props.FV_QA_REJECTED_TIMEOUT_VALUE} ${props.FV_QA_REJECTED_TIMEOUT_UNIT}</b>"
      } else {
        pBody += "<BR/><b>Feature Verification will be automatically rejected in ${props.FV_DEV_TIMEOUT_VALUE} ${props.FV_DEV_TIMEOUT_UNIT}</b>"
      }
      pBody += "</BODY>"
      return pBody
  } else if (fvStatus == "finished"){
      pBody += "<h3>The Feature Verification environment is going to stop on ${SERVER_NAME}.</h3>"
      pBody += "<h3>To check status please follow the link: ${BUILD_URL}.</h3>\n</BODY>"
      return pBody
  } else if (fvStatus == "failed"){
      pBody += "<h3>Feature Verification environment test was failed</h3>"
      pBody += "<h3>The Feature Verification environment is going to shutdown on ${SERVER_NAME}.</h3>"
      pBody += "<h3>To check status please follow the link: ${BUILD_URL}.</h3>\n</BODY>"
      return pBody
  } else if (fvStatus == "qa_rejected"){
      pBody += "<h3>Feature Verification on ${SERVER_NAME} was rejected by QA. Please review FV.</h3>\n"
      pBody += "<h3>Feature Verification environment will be automatically rejected in <b>${props.FV_QA_REJECTED_TIMEOUT_VALUE} ${props.FV_QA_REJECTED_TIMEOUT_UNIT}</b>.</h3>\n</BODY>"
      return pBody
  }
}

//subject for FV emails
def fvTemplateSubj(fvStatus){
  if (fvStatus == "preparing"){
    return """[${env.SOURCE_BRANCH}] FV environment is going to deploy """
  } else if (fvStatus == "started"){
    return """[${env.SOURCE_BRANCH}] FV environment is ready  """
  } else if (fvStatus == "finished"){
    return """[${env.SOURCE_BRANCH}] FV environment is going to stop """
  } else if (fvStatus == "failed"){
    return """[${env.SOURCE_BRANCH}] FV environment test is failed. Environment is going to stop """
  } else if (fvStatus == "qa_rejected"){
    return """[${env.SOURCE_BRANCH}] FV environment iwas rejected by QA team  """
  }
}

//Deploy start notofication
def deployStartTemplate(){
  pBody = header
  pBody += "<h3>The <b>${ENVIRONMENT}</b> environment in going to redeploy:</h3>"
  pBody += "<b>URL: </b>${BUILD_URL}<BR/>"
  pBody += "<b>GIT Branch: </b>${GIT_BRANCH}<BR/>"
  pBody += "<b>Artifact ID: </b>${buildprops.BUILD_ID}<BR/>"
  if (env.UPDATE_DB && env.UPDATE_DB == "true") {
    pBody += "<b>DB preparation: </b>Update<BR/>"
    if (env.IMPORT_DUMP){
      if (env.IMPORT_DUMP == "true"){
        if (env.ENV_DUMP == ""){
          pBody += "<b>Dump env: </b>${ENVIRONMENT}<BR/>"
        } else {
          pBody += "<b>Dump env: </b>${ENV_DUMP}<BR/>"
        }
        pBody += "<b>Dump ID: </b>${DUMP_ARTIFACT_ID}<BR/>"
      }
    }
  } else if (env.UPDATE_DB == "false"){
    pBody += "<b>DB preparation: </b><BR/>"
  } else {
    pBody += "<b>DB preparation: </b>Init<BR/>"
  }
  pBody += "</BODY>"
  return pBody
}


//Deploy finish template
def deployFinishedTemplate(pMNTestResult=null, pMNTestUrl=null){
  pBody = header
  pBody += "<TABLE><TR>"
  if (currentBuild.result == null || currentBuild.result == "SUCCESS") {
    pBody += "<TD align='right'><IMG SRC='http://icons.iconarchive.com/icons/hopstarter/scrap/32/Aqua-Ball-Green-icon.png'>"
  } else {
    pBody += "<TD align='right'><IMG SRC='http://icons.iconarchive.com/icons/hopstarter/scrap/32/Aqua-Ball-Red-icon.png'>"
  }
  pBody += "</TD><TD valign='center'><B style='font-size: 200%;'>BUILD ${currentBuild.result ?: 'SUCCESSFUL'}</B></TD></TR>"
  pBody += "<TR><TD>URL</TD><TD><A href='${BUILD_URL}'>${BUILD_URL}</A></TD></TR>"
  pBody += "<TR><TD>Project:</TD><TD>${JOB_BASE_NAME}</TD></TR>"
  pBody += "<TR><TD>GIT Branch:</TD><TD>${GIT_BRANCH}</TD></TR>"
  pBody += "<TR><TD>Artifact ID:</TD><TD>${buildprops.BUILD_ID}</TD></TR>"
  if (envprops.RN_ADD_TO_CONFLUENCE){
    if (kb_rn_page_id == ""){
      pBody += "<TR><TD>Release Notes:</TD><TD><a href='${props.RN_CONFLUENCE_URL}/display/${props.RN_PROJECT_SPACE_KEY}/${ENVIRONMENT.toUpperCase()}'>Release Notes: ${ENVIRONMENT.toUpperCase()} ${GIT_BRANCH} ${buildprops.BUILD_ID}</a></TD></TR>"
    } else {
      pBody += "<TR><TD>Release Notes:</TD><TD><a href='${props.RN_CONFLUENCE_URL}/pages/viewpage.action?pageId=${kb_rn_page_id}'>Release Notes: ${ENVIRONMENT.toUpperCase()} ${GIT_BRANCH} ${buildprops.BUILD_ID}</a></TD></TR>"
    }
  }
  if (env.UPDATE_DB && env.UPDATE_DB == "true") {
    pBody += "<TR><TD>DB preparation:</TD><TD>Update</TD></TR>"
    if (env.IMPORT_DUMP){
      if (env.IMPORT_DUMP == "true"){
        if (env.ENV_DUMP == ""){
          pBody += "<TR><TD>Dump env:</TD><TD>${ENVIRONMENT}</TD></TR>"
        } else {
          pBody += "<TR><TD>Dump env:</TD><TD>${ENV_DUMP}</TD></TR>"
        }
        pBody += "<TR><TD>Dump ID:</TD><TD>${DUMP_ARTIFACT_ID}</TD></TR>"
      }
    }
  } else if (env.UPDATE_DB == "false"){
    pBody += "<TR><TD>DB preparation:</TD></TR>"
  } else {
    pBody += "<TR><TD>DB preparation:</TD><TD>Init</TD></TR>"
  }
  if (!(pMNTestResult == "SUCCESS" || pMNTestResult == null)) {
    pBody += "<TR><TD>Manual Tests URL:</TD><TD>${pMNTestUrl}</TD></TR>"
  }
  pBody += "</TABLE><BR/>"
  pBody += createUrlTable(env.SITE_URLS)
  pBody += "</BODY>"
  return pBody

}

return this
