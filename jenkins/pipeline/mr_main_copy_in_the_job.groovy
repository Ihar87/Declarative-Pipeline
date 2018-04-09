// workaround to check out the Jenkinsfile of the tag because parameters are not replaced
// when using the "Pipeline script from SCM" option
node() {
    stage 'Load Jenkinsfile From SCM'
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          checkout poll:false, scm: [
              $class: 'GitSCM',
              branches: [[name: "origin/${env.gitlabSourceBranch}"]],
              userRemoteConfigs: [[
                  url: "${env.gitlabSourceRepoHttpUrl}",
                  credentialsId: "${env.BB_CREDENTIALS}"
              ]]
          ]
          true
        } catch(err){
          echo "Failed to checkout from Git. Will try again in 30 sec"
          sleep(30)
          false
        }
      }
    }
    load 'ci/jenkins/pipeline/ci_mr_test.groovy'
}
