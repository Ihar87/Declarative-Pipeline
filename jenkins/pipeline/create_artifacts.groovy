//Properties to create parameters for job. Just create the pipeline job and add this section as a script. Start the job.
/*properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '')),
    gitLabConnection(''), parameters([string(defaultValue: '', description: 'env name', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'GIT URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    string(defaultValue: '', description: 'git commit to checkout the current commit', name: 'GIT_COMMIT'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: '', description: 'hybris root directory where the artifacts will be restored', name: 'HYBRIS_ROOT'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/platform', description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
    string(defaultValue: '', description: 'Build ID of main job', name: 'MAIN_BUILD_ID'),
    string(defaultValue: '', description: 'Source branch', name: 'SOURCE_BRANCH'),
    string(defaultValue: '', description: 'User mail', name: 'USER_MAIL'),
    string(defaultValue: '', description: 'Template to use while artifacts creation.\n Use blank to have develop template.', name: 'TEMPLATE'),
    string(defaultValue: '', description: 'Jenkins node label name', name: 'NODE_NAME')]),
    pipelineTriggers([])])*/

node(env.NODE_NAME) {
  //cleanup workspace
  cleanWs()
  currentBuild.description = "Create artifacts for ${SOURCE_BRANCH}"
  stage("Checkout") {
    timeout(time:30, unit: 'MINUTES'){
      waitUntil{//do checkout until it is successful
        try{
          if (env.TARGET_BRANCH != null) {
            checkout(
              [$class: 'GitSCM',
                branches: [[name: "${env.GIT_COMMIT}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'CleanBeforeCheckout'], [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: "${env.TARGET_BRANCH}"]], [$class: 'LocalBranch', localBranch: "${env.TARGET_BRANCH}"]],
               submoduleCfg: [],
               userRemoteConfigs: [[credentialsId: "${env.BB_CREDENTIALS}", url: "${env.GIT_URL}"]]])
          } else {
            checkout([$class: 'GitSCM',
              branches: [[name: "${env.GIT_COMMIT}"]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [],
              userRemoteConfigs: [[credentialsId: "${env.BB_CREDENTIALS}", url: "${env.GIT_URL}"]]])
          }
          true
        } catch(err){
          echo "Failed to checkout from Git. Will try again in 30 sec"
          sleep(30)
          false
        }
      }
    }
  }
  hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"

  stage("Prepare Hybris directory"){
    sh "rm -rf ${HYBRIS_ROOT}/*"
    hybrisModule.testPrepareHybrisDir()
  }

  stage("Prepare env"){
    hybrisModule.testPrepareHybris(env.TEMPLATE)
  }

  stage("Create Artifacts") {
      hybrisModule.createArtifacts()
    }

  stage("Copy artifacts to workspace") {
      hybrisModule.copyArtifactsToWorkspace()
  }

  def props = readProperties file: "ci/conf/global.properties"

  stage("Upload artifacts") {
    hybrisModule.uploadArtifacts("${env.GIT_BRANCH}", "${env.MAIN_BUILD_ID}")
  }

  stage("Clean WORKSPACE") {
    hybrisModule.cleanWorkspace()
  }
}
