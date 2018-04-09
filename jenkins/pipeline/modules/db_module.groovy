//this is a module contains all needed methods to work with DB

//generate DB user and return 30 last symbols (Oracle restriction)
def generateDbUser(pJobName, pBuildID){
  tmpValue = "${pJobName}_${pBuildID}"
  if (tmpValue.length()>30){
    return tmpValue[-30..-1]
  } else {
    return tmpValue
  }
}

// generate DB password and return 30 last symbols (Oracle restriction)
def generateDbPassword(pJobName, pBuildID){
  tmpValue = "${pJobName}_${pBuildID}"
  if (tmpValue.length()>30){
    return tmpValue[-30..-1]
  } else {
    return tmpValue
  }
}

// create DB schema
def createSchema(pDbUser=null, pDbPassword=null){
  if (!(pDbUser)){
    pDbUser = generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
  }
  if (!(pDbPassword)){
    pDbPassword = generateDbPassword("${env.JOB_NAME}","${env.BUILD_ID}")
  }
  //Oracle DB
  if ("${props.DB_TYPE}" == "oracle") {
    ORACLE_SCRIPT = """
    alter session set "_ORACLE_SCRIPT"=true;
    CREATE USER ${pDbUser} IDENTIFIED BY ${pDbPassword} default tablespace ${envprops.DB_TABLESPACE} temporary tablespace ${envprops.DB_TABLESPACE_TEMP} quota unlimited on ${envprops.DB_TABLESPACE};
    GRANT ALL PRIVILEGES TO ${pDbUser};
    """
    writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} AS SYSDBA @db_script.sql
      """
    }
  }
  //Mysql
  if ("${props.DB_TYPE}" == "mysql") {
    MYSQL_SCRIPT = """
    CREATE DATABASE ${pDbUser} DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci;
    CREATE USER '${pDbUser}'@'%' IDENTIFIED BY '${pDbPassword}';
    GRANT ALL PRIVILEGES ON ${pDbUser} . * TO '${pDbUser}'@'%';
    """
    writeFile file: 'db_script.sql', text: MYSQL_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          mysql -h ${envprops.DB_SERVER} -u ${USERNAME} -p${USERPASSWORD} < db_script.sql
      """
    }
  }

}


//drop DB schema
def dropSchema(pDbUser=null){
  if (!(pDbUser)){
    pDbUser = generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
  }
  //Oracle DB
  if ("${props.DB_TYPE}" == "oracle") {
    ORACLE_SCRIPT = """
    begin
    for x in (
      select Sid, Serial#, machine, program
        from v\$session
        where USERNAME='${pDbUser}'
      ) loop
        execute immediate 'Alter System Kill Session '''|| x.Sid
                     || ',' || x.Serial# || ''' IMMEDIATE';
      end loop;
    end;
    /
    exec DBMS_LOCK.SLEEP(20);
    alter session set "_ORACLE_SCRIPT"=true;
    DROP USER ${pDbUser} CASCADE;
    """
    writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} AS SYSDBA @db_script.sql
      """
    }
  }
  //Mysql DB
  if ("${props.DB_TYPE}" == "mysql") {
    MYSQL_SCRIPT = """
    DROP USER ${pDbUser};
    DROP DATABASE ${pDbUser};
    """
    writeFile file: 'db_script.sql', text: MYSQL_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          mysql -h ${envprops.DB_SERVER} -u ${USERNAME} -p${USERPASSWORD} < db_script.sql
      """
    }
  }
}

//recreate DB schema
def recreateSchema(pDbUser=null, pDbPassword=null){
  if (!(pDbUser)){
    pDbUser = generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
  }
  if (!(pDbPassword)){
    pDbPassword = generateDbPassword("${env.JOB_NAME}","${env.BUILD_ID}")
  }
  if ("${props.DB_TYPE}" == "oracle") {
    ORACLE_SCRIPT = """
    begin
    for x in (
      select Sid, Serial#, machine, program
        from v\$session
        where USERNAME='${pDbUser}'
      ) loop
        execute immediate 'Alter System Kill Session '''|| x.Sid
                     || ',' || x.Serial# || ''' IMMEDIATE';
      end loop;
    end;
    /
    exec DBMS_LOCK.SLEEP(20);
    alter session set "_ORACLE_SCRIPT"=true;
    DROP USER ${pDbUser} CASCADE;
    CREATE USER ${pDbUser} IDENTIFIED BY ${pDbPassword} default tablespace ${envprops.DB_TABLESPACE} temporary tablespace ${envprops.DB_TABLESPACE_TEMP} quota unlimited on ${envprops.DB_TABLESPACE};
    GRANT ALL PRIVILEGES TO ${pDbUser};
    """
    writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} AS SYSDBA @db_script.sql
      """
    }
  }
  if ("${props.DB_TYPE}" == "mysql") {
    MYSQL_SCRIPT = """
    DROP USER ${pDbUser};
    DROP DATABASE ${pDbUser};
    CREATE DATABASE ${pDbUser} DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci;
    CREATE USER '${pDbUser}'@'%' IDENTIFIED BY '${pDbPassword}';
    GRANT ALL PRIVILEGES ON ${pDbUser} . * TO '${pDbUser}'@'%';
    """
    writeFile file: 'db_script.sql', text: MYSQL_SCRIPT
    withCredentials([usernamePassword(credentialsId: envprops.DB_MASTER_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          mysql -h ${envprops.DB_SERVER} -u ${USERNAME} -p${USERPASSWORD} < db_script.sql
      """
    }
  }

}

//add DB properties to hybris
def addDbPropsForHybris(){
  pDbUser = generateDbUser("${env.JOB_NAME}","${env.BUILD_ID}")
  pDbPassword = generateDbPassword("${env.JOB_NAME}","${env.BUILD_ID}")
  if ("${props.DB_TYPE}" == "oracle") {
    sh """
      echo "db.url=jdbc:oracle:thin:@${envprops.DB_SERVER}:${envprops.DB_PORT}:${envprops.DB_INSTANCE}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
      echo "db.username=${pDbUser}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
      echo "db.password=${pDbPassword}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
    """
  }
  if ("${props.DB_TYPE}" == "mysql") {
    sh """
      echo "db.url=jdbc:mysql://${envprops.DB_SERVER}/${pDbUser}?useConfigs=maxPerformance&characterEncoding=utf8" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
      echo "db.username=${pDbUser}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
      echo "db.password=${pDbPassword}" >> ${props.HYBRIS_ROOT}/hybris/config/local.properties
    """
  }
}

//cleanup data
def cleanupData(){
  if ("${props.DB_TYPE}" == "oracle") {
    ORACLE_GET_TABLE_SCRIPT = ""
    composedtypesTableName = ""
    sqloutput = ""
    ORACLE_SCRIPT="""
    UPDATE solrendpointurl SET p_solrserverconfig=NULL;
    DELETE FROM JGROUPSPING;
    UPDATE props SET VALUESTRING1=0 WHERE NAME='system.locked';
    commit;
    """
    //TODO add clean another data
    if (env.CLEAN_USERS == "true"){
      echo "Cleaning up users"
      ORACLE_GET_TABLE_SCRIPT = """
      SET SERVEROUTPUT ON
      DECLARE
        comptable varchar2(100);
      BEGIN
        select tablename into comptable from YDEPLOYMENTS where tablename like 'composedtypes%' ORDER BY tablename DESC FETCH FIRST 1 ROWS ONLY;
      DBMS_OUTPUT.PUT_LINE(comptable);
      END;
      /
      """
      writeFile file: 'db_script_get_table.sql', text: ORACLE_GET_TABLE_SCRIPT
      if (envprops.DB_USER_NAME){
        sqloutput = sh(script: """sqlplus64 ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script_get_table.sql""", returnStdout: true).trim()
      } else {
        withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
          sqloutput = sh(script: """sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script_get_table.sql""", returnStdout: true).trim()
        }
      }
      for (str in sqloutput.split("\n")){
        if (str.contains("composedtypes")){
            composedtypesTableName = str
        }
      }
      echo composedtypesTableName
      ORACLE_SCRIPT+="""
      delete from users \nwhere pk in (SELECT usr.pk FROM users usr INNER JOIN pgrels pgr ON pgr.sourcePK=usr.pk INNER JOIN usergroups ugs ON pgr.targetPK = ugs.pk WHERE ugs.p_uid NOT IN ('admingroup','cockpitgroup','employeegroup','csagentmanagergroup','cmsmanagergroup','customermanagergroup','productmanagergroup','customerservicegroup','csagentgroup','asagentgroup','asagentsalesgroup','asagentsalesmanagergroup','promomanagergroup','vjdbcReportsGroup','webservicegroup','analyticsperspectivegroup','customergroup','backofficeadmingroup','vjdbcReportsGroup','marketingManagerGroup','previewmanagergroup','rootcmsmanagergroup','employeePromotionGroup','salesadmingroup','salesmanagergroup','cmsmanagerreadonlygroup','salesemployeegroup','salesapprovergroup','restrictedMarketingManagerGroup','fraudAgentGroup') \nAND usr.p_uid NOT IN ('anonymous') \n AND usr.pk NOT IN (SELECT usr2.pk FROM users usr2 INNER JOIN ${composedtypesTableName} comt ON comt.pk=usr2.typepkstring where comt.internalcode = 'Employee'));
      commit;
      DELETE FROM pgrels WHERE sourcePK NOT IN (SELECT usr.pk FROM users usr);
      commit;
      DELETE FROM USERGROUPS WHERE pk NOT IN (SELECT pgr.targetPK FROM pgrels pgr);
      commit;
      UPDATE users SET p_passwordencoding = 'plain', passwd='nimda1' WHERE p_uid='admin';
      UPDATE users SET p_passwordencoding = 'plain', passwd='12341' WHERE p_uid='cmsmanager';
      UPDATE users SET p_passwordencoding = 'plain', passwd='12341' WHERE p_uid='productmanager';
      UPDATE users SET p_passwordencoding = 'plain', passwd='12341' WHERE p_uid='csagent';
      commit;
      """
    }
    if (env.CLEAN_ORDERS == "true"){
      echo "Cleaning up orders"
      ORACLE_SCRIPT+="""

      DELETE FROM orders;
      DELETE FROM orderentries;
      DELETE FROM orderdiscrels;
      DELETE FROM ordercancelconfigs;
      commit;
      """
    }
    writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
    if (envprops.DB_USER_NAME){
      sh """
          sqlplus64 ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
      """
    } else {
      withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
        sh """
            sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
        """
      }
    }
  }


}

//cleanup schema": remove everything inside schema
def cleanupSchema(){
  ORACLE_SCRIPT="""
    BEGIN
     FOR cur_rec IN (SELECT object_name, object_type
                       FROM user_objects
                      WHERE object_type IN
                               ('TABLE',
                                'VIEW',
                                'PACKAGE',
                                'PROCEDURE',
                                'FUNCTION',
                                'SEQUENCE',
                                'INDEX'
                               ))
     LOOP
        BEGIN
           IF cur_rec.object_type = 'TABLE'
           THEN
              EXECUTE IMMEDIATE    'DROP '
                                || cur_rec.object_type
                                || ' "'
                                || cur_rec.object_name
                                || '" CASCADE CONSTRAINTS';
           ELSE
              EXECUTE IMMEDIATE    'DROP '
                                || cur_rec.object_type
                                || ' "'
                                || cur_rec.object_name
                                || '"';
           END IF;
        EXCEPTION
           WHEN OTHERS
           THEN
              DBMS_OUTPUT.put_line (   'FAILED: DROP '
                                    || cur_rec.object_type
                                    || ' "'
                                    || cur_rec.object_name
                                    || '"'
                                   );
        END;
     END LOOP;
    END;
    /
    commit;
  """
  writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
  if (envprops.DB_USER_NAME){
    sh """
        sqlplus64 ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
    """
  } else {
    withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
          sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
      """
    }
  }
}

// create DB dump
def createDBDump(toArchive=true){
	ORACLE_SCRIPT="""
		CREATE OR REPLACE DIRECTORY ${envprops.DB_DUMP_ALIAS} AS '${props.DB_DUMP_FOLDER}';
		GRANT READ, WRITE ON DIRECTORY ${envprops.DB_DUMP_ALIAS} TO PUBLIC;
	"""
	writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
  withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
  	sh """
  		sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
  	"""
  }

  //and zip
  if ("${props.DB_TYPE}" == "oracle") {
    sshagent([envprops.DB_SSHAGENT_ID]) {
      if (envprops.DB_USER_NAME){
        sh """
          ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
          ssh -o StrictHostKeyChecking=no -l ${envprops.DB_CONNECT_USER} ${envprops.DB_SERVER} "expdp ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD} schemas=${envprops.DB_SCHEMA_NAME} directory=${envprops.DB_DUMP_ALIAS} dumpfile=${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp exclude=user,identity_column"
          rsync -a ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER}:${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp ${WORKSPACE}/db_dump.dmp
          ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
        """
      } else {
        withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
          sh """
            ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
            ssh -o StrictHostKeyChecking=no -l ${envprops.DB_CONNECT_USER} ${envprops.DB_SERVER} "expdp ${USERNAME}/${USERPASSWORD} schemas=${envprops.DB_SCHEMA_NAME} directory=${envprops.DB_DUMP_ALIAS} dumpfile=${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp exclude=user,identity_column"
            rsync -a ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER}:${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp ${WORKSPACE}/db_dump.dmp
            ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
          """
        }
      }

      if(toArchive){
        sh """
        zip db_dump.zip db_dump.dmp
        rm -rf db_dump.dmp
        """
      }
    }
  }
}

// download DB dump from artifactory
def downloadDump(envName=null){
  if (!(envName)){
    envName = env.ENVIRONMENT
  }
  //download and unzip
    if ("${props.DB_TYPE}" == "oracle") {
      sh """
        wget --progress=dot:giga -O db_dump.zip ${props.DUMP_ARTIFACTORY_URL}/${envName}/${DUMP_ARTIFACT_ID}/db_dump.zip
        unzip -q db_dump.zip
        rm -f db_dump.zip
      """
    }
  }

//import DB dump
def importDBDump(){
	ORACLE_SCRIPT="""
		CREATE OR REPLACE DIRECTORY ${envprops.DB_DUMP_ALIAS} AS '${props.DB_DUMP_FOLDER}';
		GRANT READ, WRITE ON DIRECTORY ${envprops.DB_DUMP_ALIAS} TO PUBLIC;
	"""
	writeFile file: 'db_script.sql', text: ORACLE_SCRIPT
  if (envprops.DB_USER_NAME){
    sh """
      sqlplus64 ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
    """
  } else {
    withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
      sh """
        sqlplus64 ${USERNAME}/${USERPASSWORD}@${envprops.DB_SERVER}:${envprops.DB_PORT}/${envprops.DB_INSTANCE} @db_script.sql
      """
    }
  }


    sshagent([envprops.DB_SSHAGENT_ID]) {
      if (envprops.DB_USER_NAME){
        sh """
          ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "exit"
          rsync -a --delete ${WORKSPACE}/db_dump.dmp ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER}:${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp
          ssh -o StrictHostKeyChecking=no -l ${envprops.DB_CONNECT_USER} ${envprops.DB_SERVER} "impdp ${envprops.DB_USER_NAME}/${envprops.DB_USER_PASSWORD} directory=${envprops.DB_DUMP_ALIAS} dumpfile=${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp full=y REMAP_SCHEMA=${sourceprops.DB_SCHEMA_NAME}:${envprops.DB_SCHEMA_NAME} REMAP_TABLESPACE=${sourceprops.DB_TABLESPACE}:${envprops.DB_TABLESPACE},${sourceprops.DB_TABLESPACE_TEMP}:${envprops.DB_TABLESPACE_TEMP} TABLE_EXISTS_ACTION=REPLACE"
          ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
        """
      } else {
        withCredentials([usernamePassword(credentialsId: envprops.DB_USER_CREDS, passwordVariable: 'USERPASSWORD', usernameVariable: 'USERNAME')]) {
          sh """
            ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "exit"
            rsync -a --delete ${WORKSPACE}/db_dump.dmp ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER}:${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp
            ssh -o StrictHostKeyChecking=no -l ${envprops.DB_CONNECT_USER} ${envprops.DB_SERVER} "impdp ${USERNAME}/${USERPASSWORD} directory=${envprops.DB_DUMP_ALIAS} dumpfile=${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp full=y REMAP_SCHEMA=${sourceprops.DB_SCHEMA_NAME}:${envprops.DB_SCHEMA_NAME} REMAP_TABLESPACE=${sourceprops.DB_TABLESPACE}:${envprops.DB_TABLESPACE},${sourceprops.DB_TABLESPACE_TEMP}:${envprops.DB_TABLESPACE_TEMP} TABLE_EXISTS_ACTION=REPLACE"
            ssh -o StrictHostKeyChecking=no ${envprops.DB_CONNECT_USER}@${envprops.DB_SERVER} "rm -rf ${props.DB_DUMP_FOLDER}/${ENVIRONMENT}_${BUILD_ID}_db_dump.dmp"
          """
        }
      }
    }
  }



return this
