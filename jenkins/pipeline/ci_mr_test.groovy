def singleTest(pJobName, pNode, pHybrisRoot, pDump, antDir, pGitBranch) {
  //run job with parameters
  if (pDump.size() == 1){ //if there is no custom dump to import
    build job: "${pJobName}", parameters: [string(name: 'ENVIRONMENT', value: props.ENVIRONMENT),
                                          string(name: 'GIT_URL', value: env.GIT_URL),
                                          string(name: 'GIT_BRANCH', value: pGitBranch),
                                          string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                          credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS"),
                                          string(name: 'HYBRIS_ROOT', value: "${pHybrisRoot}"),
                                          string(name: 'NODE_NAME', value: "${pNode}"),
                                          string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: antDir),
                                          string(name: 'TARGET_BRANCH', value: env.CHANGE_TARGET)]
  } else {
    build job: "${pJobName}", parameters: [string(name: 'ENVIRONMENT', value: props.ENVIRONMENT),
                                          string(name: 'GIT_URL', value: env.GIT_URL),
                                          string(name: 'GIT_BRANCH', value: pGitBranch),
                                          string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                          credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS"),
                                          string(name: 'HYBRIS_ROOT', value: "${pHybrisRoot}"),
                                          string(name: 'NODE_NAME', value: "${pNode}"),
                                          string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: antDir),
                                          string(name: 'TARGET_BRANCH', value: env.CHANGE_TARGET),
                                          string(name: 'DUMP_ARTIFACT_ID', value: pDump[1]),
                                          string(name: 'ENV_DUMP', value: pDump[0])]
        }

}

pipeline {
  agent {
    node {
      label 'master'
    }
  }
  environment {
    props = ""
    dumpList = ""
    labelList = list()
    branchName = ""
    authorEmail = ""
    mr_approved = ""
  }
  stages {
    stage ("Git"){
      options {
        timeout(time: 10, unit: "MINUTES")
        retry(3)
      }
      steps {
        cleanWs()
        checkout([$class: 'GitSCM', branches: [[name: env.GIT_COMMIT]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: "BB_CREDENTIALS", url: env.GIT_URL]]])
      }
      post {
        failure {
          echo "Git checkout was failed"
        }
      }
    }

    stage("Get properties"){
      steps {
        script {
          props = readProperties file: "ci/conf/global.properties"
          withCredentials([usernamePassword(credentialsId: "BB_CREDENTIALS", passwordVariable: 'BBPASSWD', usernameVariable: 'BBUSER')]) {
            mrgbl = sh (script: """python ci/jenkins/bitbucket_utils.py --is-mergeable --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
            echo "MR is mergeable: " + mrgbl
            if (mrgbl != "True") {
              error "Pull Request is not mergeable"
            }

            def js = sh (script: """python ci/jenkins/bitbucket_utils.py --get-labels --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
            labelList = js.split(",")
            echo labelList.toString()
            //get branch name
            branchName = sh (script: """python ci/jenkins/bitbucket_utils.py --get-branch-name --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
            //get author email
            authorEmail = sh (script: """python ci/jenkins/bitbucket_utils.py --get-author-email --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
            // [TODO] Add read mergeability
            // [TODO] Add get dump name
            dumpList = dumpList.split(",")
          }
        }
      }
    }

    stage("Pipeline Syntax Check"){
      steps {
        build job: "ci_syntax_check_test", parameters: [string(name: 'GIT_BRANCH', value: env.GIT_COMMIT),
                                                        credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS")]
      }
      post {
        failure {
          echo "Pipelines syntax check is failed"
        }
      }
    }



    stage ("Jobs"){
      parallel {
        stage("Code Quality"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "CodeQuality test"
            //singleTest("ci_codequality_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Junit"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "Junit test"
            //singleTest("ci_junit_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Junit init and integrational"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "Integrational test"
            //singleTest("ci_integration_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Webtests"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "WebAlltest test"
            //singleTest("ci_allwebtest_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Initialization"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "DB Init test"
            //singleTest("ci_initialization_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Incremental update"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "Incremental test"
            //singleTest("ci_incremental_update_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("Sonar"){
          when {
            expression { labelList.contains('codequality') }
          }
          steps {
            echo "Sonar test"
            //singleTest("ci_sonar_test", props.SLAVE_NODE_LABEL, props.HYBRIS_ROOT,dumpList, props.CUSTOM_ANT_SCRIPT_DIR, branchName)
          }
        }

        stage("FV Deploy and Manual tests"){
          when {
            anyOf {
              expression { labelList.contains('keep_fv') }
              expression {! labelList.contains('skip_spock') }
              expression {! labelList.contains('skip_all') }
              expression {! labelList.contains('!!!!ToRemove') }
            }

          }
          steps {
            build job: "sub_create_artifact", parameters: [string(name: 'ENVIRONMENT', value: props.ENVIRONMENT),
                                                                string(name: 'GIT_URL', value: env.GIT_URL),
                                                                string(name: 'GIT_BRANCH', value: branchName),
                                                                string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                                                credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS"),
                                                               string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
                                                                string(name: 'NODE_NAME', value: props.SLAVE_NODE_LABEL),
                                                                string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: props.CUSTOM_ANT_SCRIPT_DIR),
                                                                string(name: 'MAIN_BUILD_ID', value: env.BUILD_ID),
                                                                string(name: 'SOURCE_BRANCH', value: branchName),
                                                                string(name: 'USER_MAIL', value: authorEmail),
                                                                string(name: 'TEMPLATE', value: props.TEMPLATE),]
            script{
              if (lList.contains("deploy_init") || lList.contains("deploy_update")){
                    if (lList.contains("deploy_init")){
                      deployMethod = "init"
                    } else {
                      deployMethod = "update"
                    }
                  } else {
                    deployMethod = props.FV_DB_PREPARATION
                  }
              if (dumpList.size() == 1){ //if there is no custom dump
                build job: "FV_deploy", parameters: [string(name: 'ENVIRONMENT', value: props.FV_ENVIRONMENT),
                                                    string(name: 'GIT_URL', value: env.GIT_URL),
                                                    string(name: 'GIT_BRANCH', value: branchName),
                                                    credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS"),
                                                    string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
                                                    string(name: 'NODE_NAME', value: props.DEPLOY_NODE_LABEL),
                                                    string(name: 'DB_PREPARE_METHOD', value: deployMethod),
                                                    string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: props.CUSTOM_ANT_SCRIPT_DIR),
                                                    string(name: 'MAIN_BUILD_ID', value: env.BUILD_ID),
                                                    string(name: 'SOURCE_BRANCH', value: branchName),
                                                    string(name: 'USER_MAIL', value: authorEmail),
                                                    string(name: 'TEST_ENVIRONMENT', value: props.ENVIRONMENT),
                                                    string(name: 'TEST_NODE', value: props.SLAVE_NODE_LABEL),
                                                    string(name: 'TEST_TARGET_BRANCH', value: env.CHANGE_TARGET),
                                                    string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                                    booleanParam(name: 'SPOCK_TEST_RUN', value: (! labelList.contains("skip_spock"))),
                                                    booleanParam(name: 'KEEP_FV_RUNNING', value: (labelList.contains("keep_fv"))),
                                                    booleanParam(name: 'FV_QA_REVIEW', value: (labelList.contains("fv_review"))),
                                                    text(name: 'SITE_URLS', value: props.SITE_URLS)]
              } else {
                build job: "FV_deploy", parameters: [string(name: 'ENVIRONMENT', value: props.FV_ENVIRONMENT),
                                                    string(name: 'GIT_URL', value: env.GIT_URL),
                                                    string(name: 'GIT_BRANCH', value: branchName),
                                                    credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: "BB_CREDENTIALS"),
                                                    string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
                                                    string(name: 'NODE_NAME', value: props.DEPLOY_NODE_LABEL),
                                                    string(name: 'DB_PREPARE_METHOD', value: deployMethod),
                                                    string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: props.CUSTOM_ANT_SCRIPT_DIR),
                                                    string(name: 'MAIN_BUILD_ID', value: env.BUILD_ID),
                                                    string(name: 'SOURCE_BRANCH', value: branchName),
                                                    string(name: 'USER_MAIL', value: authorEmail),
                                                    string(name: 'TEST_ENVIRONMENT', value: props.ENVIRONMENT),
                                                    string(name: 'TEST_NODE', value: props.SLAVE_NODE_LABEL),
                                                    string(name: 'TEST_TARGET_BRANCH', value: env.CHANGE_TARGET),
                                                    string(name: 'GIT_COMMIT', value: env.GIT_COMMIT),
                                                    booleanParam(name: 'SPOCK_TEST_RUN', value: (! labelList.contains("skip_spock"))),
                                                    booleanParam(name: 'KEEP_FV_RUNNING', value: (labelList.contains("keep_fv"))),
                                                    booleanParam(name: 'FV_QA_REVIEW', value: (labelList.contains("fv_review"))),
                                                    text(name: 'SITE_URLS', value: props.SITE_URLS),
                                                    string(name: 'DUMP_ARTIFACT_ID', value: dumpList[1]),
                                                    string(name: 'ENV_DUMP', value: dumpList[0])]
              }
            }
          }
          post{
            always {
              script {
                withCredentials([usernamePassword(credentialsId: "BB_CREDENTIALS", passwordVariable: 'BBPASSWD', usernameVariable: 'BBUSER')]) {
                  mrgbl = sh (script: """python ci/jenkins/bitbucket_utils.py --is-mergeable --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
                  echo "MR is mergeable: " + mrgbl
                  if (mrgbl != "True") {
                    error "Pull Request is not mergeable"
                  }
                  mr_approved = sh (script: """python ci/jenkins/bitbucket_utils.py --is-approved --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
                  def js = sh (script: """python ci/jenkins/bitbucket_utils.py --get-labels --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """, returnStdout: true).trim()
                  labelList = js.split(",")
                  echo labelList.toString()
                }
              }
            }
          }
        }


        stage("Merge"){
          when {
            environment name: 'mrgbl', value: 'True'
            environment name: 'mr_approved', value: 'True'
            expression { labelList.contains('merge') }
            }
          steps {
            echo "!!!WILL BE MERGED!!!"
            //sh """python ci/jenkins/bitbucket_utils.py --get-labels --server-url ${props.BITBUCKET_URL} --user ${BBUSER} --password '${BBPASSWD}' --project ${props.BITBUCKET_PROJECT} --repo ${props.BITBUCKET_REPOSITORY} --pr ${CHANGE_ID} """
          }
          post {
            failure {
              echo "Merge was failed"
              emailext body: "${branchName} merge to ${CHANGE_TARGET} was failed. Please check job ${BUILD_URL}. ", recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Merge of ${branchName} are failed', to: authorEmail
            }
          }
        }

      }
    }
  }
  post {
    failure {
      emailext body: "${branchName} test is failed. Please check job ${BUILD_URL}. ", recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests of ${branchName} are failed', to: authorEmail
    }
    aborted {
      emailext body: "${branchName} test is aborted. Please check job ${BUILD_URL}. ", recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests of ${branchName} were aborted', to: authorEmail
    }
  }
}
