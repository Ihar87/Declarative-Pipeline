/*properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '')),
    gitLabConnection(''), parameters([string(defaultValue: '', description: 'env name', name: 'ENVIRONMENT'),
    string(defaultValue: '', description: 'Git URL', name: 'GIT_URL'),
    string(defaultValue: '', description: 'git branch name', name: 'GIT_BRANCH'),
    credentials(credentialType: 'com.cloudbees.plugins.credentials.common.StandardCredentials', defaultValue: '', description: 'credentials for git storage', name: 'BB_CREDENTIALS', required: false),
    string(defaultValue: '/opt/hybris/hybris', description: 'hybris directory where the artifacts will be restored', name: 'HYBRIS_ROOT'),
    string(defaultValue: '${HYBRIS_ROOT}/hybris/bin/custom/buildscripts/resources/buildscripts/ant', description: 'directory with custom ant script', name: 'CUSTOM_ANT_SCRIPT_DIR'),
    string(defaultValue: 'production', description: 'Hybris template will be used in artifacts creation', name: 'TEMPLATE'),
    string(defaultValue: '', description: 'Email will be sent to this user or list separated by comma', name: 'USER_MAIL'),
    string(defaultValue: "sslave", description: 'jenkins slave nodes label to run tests', name: 'SLAVE_NODE_LABEL')]),
    pipelineTriggers([])])*/

@NonCPS
def getRec(){
  pBody = ""
  def changeLogSets = currentBuild.changeSets
  for (int i = 0; i < changeLogSets.size().intdiv(2); i++) {
    def entries = changeLogSets[i].items
    for (int j = 0; j < entries.length; j++) {
        def entry = entries[j]
				pBody += "<TABLE>\n"
        pBody += "<TR><TH>Commit ID</TH><TD>${entry.commitId}</TD></TR><TR><TH>Author</TH><TD>${entry.author}</TD></TR><TR><TH>Date</TH><TD>${new Date(entry.timestamp)}</TD></TR><TR><TH>Description</TH><TD>${entry.msg}</TD></TR>\n"
				pBody += "</TABLE><BR/>\n"
        def files = new ArrayList(entry.affectedFiles)
				pBody += "<TABLE>\n<TR>\n<TH>Action</TH><TH>File path</TH>\n</TR>\n"
        for (int k = 0; k < files.size(); k++) {
            def file = files[k]
            pBody += "<TR><TD>${file.editType.name}</TD><TD>${file.path}</TD></TR>\n"
        }
        pBody += "</TABLE>\n<BR/><BR/>\n"
    }
  }
  return pBody
}

// Make GIT_COMMIT variable global
GIT_COMMIT = ""
//make props global
props = ""
//add build description
currentBuild.description = currentBuild.description + "\n${GIT_BRANCH}"

//checkout git repo without local merge
stage("Git"){
  node() { //run on master node
    timeout(time:30, unit: 'MINUTES'){
      //git branch: "${GIT_BRANCH}", credentialsId: "$BB_CREDENTIALS", url: "$GIT_URL"
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


    if (env.gitlabMergeRequestLastCommit){
      GIT_COMMIT = env.gitlabMergeRequestLastCommit
    } else {
      echo "Get GIT_COMMIT from file"
    GIT_COMMIT = readFile(".git/HEAD").trim()
    }
	//load global properties file with variables for CI
	props = readProperties file: "ci/conf/global.properties"
  }
}

//start tests. Covered by try to catch job abort or fail one of the tests
  stage("Run tests") {
		try{
			//start jobs in parallel without early fail. To turn the abort all jobs if one is failed use failFast=true in parallel operation
			parallel (
				"Code Quality tests": {

					//start test job with parameters
					build job: 'ci_codequality_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},
				//start test job with parameters
				"Junit": {
					build job: 'ci_junit_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},
				//start test job with parameters
				"Junit init and integrational tests": {
					build job: 'ci_integration_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},

				//start test job with parameters
				"Webtests": {
					build job: 'ci_allwebtest_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},
				//start test job with parameters
				"Initialization": {
					build job: 'ci_initialization_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},
				//start test job with parameters
				"Update": {
					build job: 'ci_incremental_update_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
																										string(name: 'GIT_URL', value: env.GIT_URL),
																										string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
																										credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
																										string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
																										string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
																										string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR),
																										string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
				},
        "Sonar_branch_test": {
        build job: 'ci_sonar_branch_test', parameters: [string(name: 'ENVIRONMENT', value: env.ENVIRONMENT),
                                                  string(name: 'GIT_URL', value: env.GIT_URL),
                                                  string(name: 'GIT_BRANCH', value: env.GIT_BRANCH),
                                                  credentials(description: 'credentials for git storage', name: 'BB_CREDENTIALS', value: env.BB_CREDENTIALS),
                                                  string(name: 'HYBRIS_ROOT', value: props.HYBRIS_ROOT),
                                                  string(name: 'NODE_NAME', value: env.SLAVE_NODE_LABEL),
                                                  string(name: 'CUSTOM_ANT_SCRIPT_DIR', value: env.CUSTOM_ANT_SCRIPT_DIR)
                                                  string(name: 'GIT_COMMIT', value: GIT_COMMIT)]
      }
				//,failFast=true

			)
		}catch (InterruptedException x) {
        currentBuild.result = 'ABORTED'
				//throw x
				emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
				error "CI tests aborted"
      } catch (err) {
        currentBuild.result = 'FAILURE'
				//throw err
				emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
				error "CI tests failed"
      } finally {
        if ((currentBuild.result == "FAILURE") || (currentBuild.result == "ABORTED")){
          emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
        }
      }
  }
      //run on master node
      node(env.SLAVE_NODE_LABEL){
        //cleanup workspace
        cleanWs()
				try{
				//git branch: "${GIT_BRANCH}", credentialsId: "$BB_CREDENTIALS", url: "$GIT_URL"
        timeout(time:30, unit: 'MINUTES'){
          waitUntil{//do checkout until it is successful
						try{
							checkout([$class: 'GitSCM', branches: [[name: GIT_COMMIT]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: env.BB_CREDENTIALS, url: env.GIT_URL]]])
							true
						} catch(err){
							echo "Failed to checkout from Git. Will try again in 30 sec"
							sleep(30)
							false
						}
					}
        }

				//load global properties file with variables for CI
			props = readProperties file: "ci/conf/global.properties"
      //load module
      hybrisModule = load "ci/jenkins/pipeline/modules/hybris_module.groovy"
			templateModule = load "ci/jenkins/pipeline/modules/emailtemplates_module.groovy"
			envprops = readProperties file: "ci/conf/${ENVIRONMENT}.properties"


      //prepare hybris directory. Get more info in the module
      stage("Prepare Hybris directory"){
        hybrisModule.testPrepareHybrisDir()
      }

      //prepare hybris. Get more info in the module
      stage("Prepare env") {
        hybrisModule.testPrepareHybris("${env.TEMPLATE}")
      }

    //create artifacts for the upload. Get more info in the module
    stage("Create Artifacts") {
        hybrisModule.createArtifacts()
      }
    //copy artifacts to WORKSPACE. Get more info in the module
    stage("Copy artifacts to workspace") {
        hybrisModule.copyArtifactsToWorkspace()
    }


    //upload artifact to AS. Get more info in the module
    stage("Upload artifacts") {
      hybrisModule.uploadArtifacts()
			hybrisModule.sendArtifactsCreatedEmail(getRec())


      }


    } catch (InterruptedException x) {
        currentBuild.result = 'ABORTED'
				emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
				throw x
				error "CI tests aborted"
      } catch (err) {
        currentBuild.result = 'FAILURE'
				emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
				throw err
				error "CI tests failed"
      } finally {
				stage("Buildkit update"){
					if (currentBuild.result == null){
/*
BUILDKIT
						sh """
					    ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} mkdir -p ${props.BUILDKIT_FOLDER}/${GIT_BRANCH}
					    rsync -apv --delete ${HYBRIS_ROOT}/hybris/bin/custom/hybrisaccelerator/hybrisacceleratorstorefront/web/webroot/_ui/responsive/theme-delta/ ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.BUILDKIT_FOLDER}/${GIT_BRANCH}/
					    rsync -apv --delete ${HYBRIS_ROOT}/hybris/bin/custom/hybrisaccelerator/hybrisacceleratorstorefront/web/webroot/_ui/responsive/common ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.BUILDKIT_FOLDER}/${GIT_BRANCH}/
					    scp -r ${HYBRIS_ROOT}/ci/jenkins/templates ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.BUILDKIT_FOLDER}
					    scp ${HYBRIS_ROOT}/ci/jenkins/buildkit_html_generator.py ${props.ARTIFACT_USER}@${props.SERVER_NAME}:${props.BUILDKIT_FOLDER}
					    ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} "cd ${props.BUILDKIT_FOLDER}; python ./buildkit_html_generator.py"
					    ssh -o StrictHostKeyChecking=no ${props.ARTIFACT_USER}@${props.SERVER_NAME} 'for A in ${props.BUILDKIT_FOLDER}/${GIT_BRANCH}/pages/*.html; do sed -i -e "s/\\.\\.\\/\\.\\./\\.\\./g" '\\\${A}'; done'
					  """
*/
					  emailext mimeType: 'text/html', body: templateModule.buildKitTemplate(), subject: "BuildKit for ${GIT_BRANCH} branch was updated", to: env.BUILDKIT_EMAIL_LIST

					}
				}

        if ((currentBuild.result == "FAILURE") || (currentBuild.result == "ABORTED")){
          emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
        }
      }

			if ((currentBuild.result == "FAILURE") || (currentBuild.result == "ABORTED")){
				emailext body: 'Check the ${JOB_URL} with a build number ${BUILD_ID}', recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']], subject: 'Tests for ${GIT_BRANCH} branch are failed', to: "${env.USER_MAIL},${props.EMAIL_DEV_GROUP}"
			}

	}
