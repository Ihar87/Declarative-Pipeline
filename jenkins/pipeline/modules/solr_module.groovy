//this module contains solr managing methods

def startFVSolr(){
  sh """
    cd /opt/hybris/hybris/bin/ext-commerce/solrserver/resources/solr/bin
    chmod +x ./solr
    ./solr start -p 8983
  """
}

def stopFVSolr(){
  sh """
    cd /opt/hybris/hybris/bin/ext-commerce/solrserver/resources/solr/bin
    chmod +x ./solr
    ./solr stop -p 8983
  """
}

//update solr files
def updateSolrFiles(String pSshagent, String serverName, String pSudoUser, String pUser){
  if (envprops.SOLR_MODE == "cloud"){
    sshagent([pSshagent]) {
      sh """
        ssh -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "exit"
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "mkdir -p /tmp/solrupdate"
        rsync -a --delete configtemplates/base/customize/ext-commerce/solrserver/resources/solr/ ${pSudoUser}@${serverName}:/tmp/solrupdate
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su ${pUser} -c 'rsync -av /tmp/solrupdate/ ${envprops.SOLR_ROOT_FOLDER}/'"
      """
    }
  }
}

//update zookeeper config
def updateZKConfig(String pSshagent, String serverName, String pSudoUser, String pUser){
  if (envprops.SOLR_MODE == "cloud"){
    sshagent([pSshagent]) {
      sh """
        ssh -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "exit"
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su ${pUser} -c '${envprops.SOLR_ROOT_FOLDER}/bin/solr zk -upconfig -n default -d "${envprops.SOLR_ROOT_FOLDER}/server/solr/configsets/default/conf" -z ${envprops.ZK_UPDATE_HOST_PORT}'"
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su ${pUser} -c '${envprops.SOLR_ROOT_FOLDER}/bin/solr zk -upconfig -n backoffice -d "${envprops.SOLR_ROOT_FOLDER}/server/solr/configsets/backoffice/conf" -z ${envprops.ZK_UPDATE_HOST_PORT}'"
      """
    }
  }
}

// restart solr service
def restartSolr(String pSshagent, String serverName, String pUser){
  if (envprops.SOLR_MODE == "cloud"){
    sshagent([pSshagent]) {
      sh """
        ssh -o StrictHostKeyChecking=no ${pUser}@${serverName} "exit"
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'systemctl restart solr'"
      """
    }
  }
}

// update solr config
def updateSolr(){
  if (envprops.SOLR_MODE == "cloud"){
    solrServerList = envprops.SOLR.split(",")
    for (int i=0; i< solrServerList.size(); i++){
      updateSolrFiles(envprops.SOLR_SSHAGENT_ID, solrServerList[i], props.SOLR_ROOT_USER, props.SOLR_USER)
      restartSolr(envprops.SOLR_SSHAGENT_ID, solrServerList[i], props.SOLR_ROOT_USER)
      if (i==0){
        updateZKConfig(envprops.SOLR_SSHAGENT_ID, solrServerList[i], props.SOLR_ROOT_USER, props.SOLR_USER)
      }
    }
  }
}

//to backup index
def solrIndexBackup(String pSshagent, String serverName, String pSudoUser, String pUser, String pCollectionName){
  tmpSolrDir = envprops.SOLR_BACKUP_RESTORE_FOLDER
  if (envprops.SOLR_MODE == "cloud"){
    sshagent([pSshagent]) {
      sh """
        ssh -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "exit"
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su - -c 'if [ ! -d ${tmpSolrDir} ]; then mkdir ${tmpSolrDir} && chmod 777 ${tmpSolrDir}; fi'"
        ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su - -c 'if [ -d ${tmpSolrDir} ]; then rm -rf ${tmpSolrDir}/*; fi'"
        curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETESTATUS&requestid=1599"
        curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=BACKUP&name=${pCollectionName}&collection=${pCollectionName}&location=${tmpSolrDir}&async=1599"
      """
      if (getStatus("cloudBackup", serverName, pCollectionName)){
        echo "${pCollectionName} export was successful"
        sh """
          rsync -a ${pSudoUser}@${serverName}:${tmpSolrDir}/${pCollectionName} solr_backup/
          ssh -t -t -o StrictHostKeyChecking=no ${pSudoUser}@${serverName} "sudo su ${pUser} -c 'rm -rf ${tmpSolrDir}/${pCollectionName}'"
        """
      } else {
        echo "${pCollectionName} export was failed"
      }
    }
    sh """
    curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETESTATUS&requestid=1599"
    """
  }
}

// copy index backup to artifactory server
def uploadSolrBackup(String pSshagent, String serverName, String pUser, String pCollectionName){
  if (props.SOLR_BACKUP_ARTIFACT_STORAGE == "http"){
    sshagent([pSshagent]){
      sh """
        ssh -o StrictHostKeyChecking=no ${pUser}@${serverName} "exit"
        ssh -o StrictHostKeyChecking=no ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName} mkdir -p ${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}
        """
        if (versionedList.contains(pCollectionName)) {
          collectionVersionID = env[props["SOLR_${pCollectionName}"]]
          if (collectionVersionID == "") {
            collectionVersionID = "latest"
          }
          if (env.OVERRIDE=="true"){
            echo "${pCollectionName}/${collectionVersionID} exists, making backup of current version"
            sh """
            ssh -o StrictHostKeyChecking=no ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName} mkdir -p ${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/${collectionVersionID}
            rsync -bav --suffix=_`date +%F` solr_backup/${pCollectionName}.zip ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName}:${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/${collectionVersionID}/
            """
          } else {
            filecheck_output = sh(script: """
              ssh -o StrictHostKeyChecking=no ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName} "if [ -f ${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/${collectionVersionID}/${pCollectionName}.zip ]; then echo '1'; else echo '0'; fi"
            """, returnStdout: true).trim()
            if (filecheck_output == "1") {
              error "File with ID ${collectionVersionID} exists."
            }
            sh """
            ssh -o StrictHostKeyChecking=no ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName} mkdir -p ${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/${collectionVersionID}
            rsync -av solr_backup/${pCollectionName}.zip ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName}:${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/${collectionVersionID}/
            """
          }
        } else {
          sh """
            rsync -av solr_backup/${pCollectionName}.zip ${props.SOLR_BACKUP_ARTIFACT_USER}@${serverName}:${props.SOLR_BACKUP_ROOT_FOLDER}/${pCollectionName}/
          """
        }
    }
  }
}

// remove solr index temporary dir used to store index backup
def removeSolrTempDir(String pSshagent, String serverName, String pUser){
  tmpSolrDir = envprops.SOLR_BACKUP_RESTORE_FOLDER
  sshagent([pSshagent]){
    sh """
      ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'rm -rf ${tmpSolrDir}/*'"
    """
  }
}

// upload index to solr
def restoreSolrDir(String pSshagent, String serverName, String pUser, String pCollectionName){
  tmpSolrDir = envprops.SOLR_BACKUP_RESTORE_FOLDER
  if (props.SOLR_BACKUP_ARTIFACT_STORAGE == "http"){
    sshagent([pSshagent]){
      sh """
        ssh -o StrictHostKeyChecking=no ${pUser}@${serverName} "exit"
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'if [ -d ${tmpSolrDir}/${pCollectionName} ]; then rm -rf ${tmpSolrDir}/${pCollectionName}; fi'"
        ssh -t -t -o StrictHostKeyChecking=no ${pUser}@${serverName} "sudo su - -c 'if [ ! -d ${tmpSolrDir} ]; then mkdir -p ${tmpSolrDir} && chmod -R 777 ${tmpSolrDir}; fi'"
      """
      if (versionedList.contains(pCollectionName)) {
        collectionVersionID = localprops[props["SOLR_ID_${pCollectionName}"]]
        if (collectionVersionID == null) {
          error "Could not found version to restore for ${pCollectionName}. Please check local.properties for ${props["SOLR_ID_${pCollectionName}"]} key."
        }
        sh """
        ssh -o StrictHostKeyChecking=no ${pUser}@${serverName} "cd ${tmpSolrDir} && wget --progress=dot:giga --quiet -O ${pCollectionName}.zip ${props.SOLR_BACKUP_ARTIFACTORY_URL}/${pCollectionName}/${collectionVersionID}/${pCollectionName}.zip && unzip -q ${pCollectionName}.zip"
        """
      } else {
        sh """
        ssh -o StrictHostKeyChecking=no ${pUser}@${serverName} "cd ${tmpSolrDir} && wget --progress=dot:giga --quiet -O ${pCollectionName}.zip ${props.SOLR_BACKUP_ARTIFACTORY_URL}/${pCollectionName}/${pCollectionName}.zip && unzip -q ${pCollectionName}.zip"
        """
      }
    }
  }
}

// upload index to solr
def restoreSolrBackup(String pSshagent, String serverName, String pUser, String pCollectionName, pConfigSet=null){
  tmpSolrDir = envprops.SOLR_BACKUP_RESTORE_FOLDER
  if (envprops.SOLR_MODE == "cloud"){
    sh """
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETESTATUS&requestid=1599"
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETE&name=${pCollectionName}&async=1599"
    """
    if (getStatus("collectionRemove", serverName, pCollectionName)){
      echo "${pCollectionName} remove was successful"
    } else {
      echo "${pCollectionName} remove was failed"
    }
    sh """
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETESTATUS&requestid=1599"
    """
    sh """
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=RESTORE&name=${pCollectionName}&collection=${pCollectionName}&location=${tmpSolrDir}&async=1599"
    """
    if (getStatus("cloudRestore", serverName, pCollectionName)){
      echo "${pCollectionName} restore was successful"
    } else {
      echo "${pCollectionName} restore was failed"
    }
    sh """
    curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=DELETESTATUS&requestid=1599"
    """
  }
  if (envprops.SOLR_MODE == "single"){
    sh """
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/cores?action=CREATE&name=${pCollectionName}&configSet=${pConfigSet}"
      curl "http://${serverName}:${envprops.SOLR_PORT}/solr/${pCollectionName}/replication?command=restore&location=${tmpSolrDir}/${pCollectionName}&name=shard1"
    """
    if (getStatus("singleRestore", serverName, pCollectionName)){
      echo "${pCollectionName} restore was successful"
    } else {
      echo "${pCollectionName} restore was failed"
    }
  }
}

// method to check if 2 strings are matches
def checkMatch(String toCheck, String pMode){
  if (pMode == "single"){
    def resTemp = (toCheck =~ /.*<str name="status">([A-Za-z\s]+)<.*/)
    if (resTemp.matches()) {
      return resTemp[0][1]
    } else {
      return ""
    }
  } else if (pMode == "cloud"){
    def resTemp = (toCheck =~ /.*<str name="state">([A-Za-z\s]+)<.*/)
    if (resTemp.matches()) {
      return resTemp[0][1]
    } else {
      return ""
    }
  }
}

//get status of operation
def getStatus(String pStatusType, String serverName, String pCollectionName){
  toReturn = true
  if (pStatusType == "singleBackup"){
    wResult = true
    waitUntil{
      resOut = sh(script:"""
        curl "http://${serverName}:${envprops.SOLR_PORT}/solr/${pCollectionName}/replication?command=details"
      """, returnStdout: true).trim()
      echo resOut
      def resultList = resOut.split("\n")
      for (int i=0; i< resultList.size(); i++){
        res2 = checkMatch(resultList[i], "single")
        if (res2 != ""){
          echo res2
          if (res2 == "success"){
            toReturn = true
            wResult = true
          } else if (res2 == "failed") {
            toReturn = false
            wResult = true
          } else {
            sleep 10
            wResult = false
          }
        }
      }
      wResult
    }
  } else if (pStatusType == "singleRestore"){
    wResult = true
    waitUntil{
      resOut = sh(script:"""
        curl "http://${serverName}:${envprops.SOLR_PORT}/solr/${pCollectionName}/replication?command=restorestatus"
      """, returnStdout: true).trim()
      echo resOut
      def resultList = resOut.split("\n")
      for (int i=0; i< resultList.size(); i++){
        res2 = checkMatch(resultList[i], "single")
        if (res2 != ""){
          echo res2
          if (res2 == "success"){
            toReturn = true
            wResult = true
          } else if (res2 == "failed") {
            toReturn = false
            wResult = true
          } else {
            sleep 10
            wResult = false
          }
        }
      }
      wResult
    }
  } else if (pStatusType in ["cloudRestore", "cloudBackup", "collectionRemove"]){
    wResult = true
    waitUntil{
      resOut = sh(script:"""
        curl "http://${serverName}:${envprops.SOLR_PORT}/solr/admin/collections?action=REQUESTSTATUS&requestid=1599"
      """, returnStdout: true).trim()
      echo resOut
      def resultList = resOut.split("\n")
      for (int i=0; i< resultList.size(); i++){
        res2 = checkMatch(resultList[i], "cloud")
        if (res2 != ""){
          echo res2
          if (res2 == "completed"){
            toReturn = true
            wResult = true
          } else if (res2 == "failed") {
            toReturn = false
            wResult = true
          } else {
            sleep 10
            wResult = false
          }
        }
      }
      wResult
    }
  }
  return toReturn
}

return this
