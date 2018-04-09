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


node() {
  //cleanup workspace
  cleanWs()
  stage("Checkout") {
    echo "checkout branch"
    timeout(time:30, unit: 'MINUTES'){
    waitUntil{//do checkout until it is successful
      try{
        checkout([$class: 'GitSCM',
          branches: [[name: env.GIT_BRANCH]],
          doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
          userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
        true
      } catch(err){
        echo "Failed to checkout from Git. Will try again in 30 sec"
        sleep(30)
        false
      }
    }
    }
}


    grFiles = findFiles glob: 'ci/jenkins/pipeline/**/*.groovy'
    sh "wget -O jenkins-cli.jar ${env.JENKINS_URL}jnlpJars/jenkins-cli.jar"
    failedList = []
       withCredentials([usernamePassword(credentialsId: "Jenkins_http_connection", passwordVariable: 'TOKEN', usernameVariable: 'USERNAME')]) {
        for (grFile in grFiles){
            echo "\n\nChecking ${grFile.name}..."
            try{
                sh "java -jar jenkins-cli.jar -http -auth ${USERNAME}:${TOKEN} -s ${env.JENKINS_URL} declarative-linter < ${WORKSPACE}/${grFile.path}"
            } catch (err){
                failedList.add(grFile.name)
            }
        }
}

    echo "Failed Files : \n ${failedList.toString()}"
    if (! failedList.isEmpty()) {
        error "Errors are during pipelines verification"
    }
    cleanWs()
}
