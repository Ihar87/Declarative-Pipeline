/*
To make this pipeline work - add global credential with ID "Jenkins_http_connection",name it like "build_runner" and copy API Token from user config page to password field in credentials.
By steps:
1. Main Jenkins page - "Manage Jenkins" - "Manage users" - Create user "build_runner"
2. On the main Jenkins page go to "Credentials" - "System" - "Global credentials" - Click "Add credentials"
3. Fill the fields:
   3.1. Username - "build_runner"
   3.2. Password will be the value of API token (Main Jenkins page - "Manage Jenkins" - "Manage users" - "build_runner" - "Settings" - Click "Show API token")
   3.3. ID is "Jenkins_http_connection"
   3.4. Comment - "for connection via jenkins cli"
   3.5. Scope -"Global"
   3.6. Save
*/
/*
properties([
  parameters([
    string(defaultValue: '', description: 'GIT URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false)
    ]),
  pipelineTriggers([])
])
*/

pipeline {
  agent {
    node {
      label 'master'
    }
  }
  environment {
    failedList = []
  }
  stages {
    stage ("Git"){
      options {
        timeout(time: 10, unit: "MINUTES")
        retry(3)
      }
      steps {
        cleanWs()
        checkout([$class: 'GitSCM', branches: [[name: env.GIT_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: "BB_CREDENTIALS", url: env.GIT_URL]]])
      }
      post {
        failure {
          echo "Git checkout was failed"
        }
      }
    }
    stage("Syntax check"){
      steps {
        script {
          grFiles = findFiles glob: 'ci/jenkins/pipeline/**/*.groovy'
          sh (script: """wget -O jenkins-cli.jar ${env.JENKINS_URL}jnlpJars/jenkins-cli.jar"""
          withCredentials([usernamePassword(credentialsId: "Jenkins_http_connection", passwordVariable: "12345678", usernameVariable: "build_runner"]) {
            for (grFile in grFiles){
                echo "\n\nChecking ${grFile.name}..."
                sh (script: """java -jar jenkins-cli.jar -http -auth ${USERNAME}:${TOKEN} -s ${env.JENKINS_URL} declarative-linter < ${WORKSPACE}/${grFile.path}"""
                }
            post {
              failure {
                echo "Errors are during pipelines verification"
                }
          }
        }
      }
     }
      steps {
        cleanWs()
     }
  }
}
}
