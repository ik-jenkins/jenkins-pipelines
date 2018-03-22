pipeline {
    agent {
        label 'micro-amazon'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    triggers {
        cron('H * * * *')
    }
    stages {
        stage('List instances') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        is_shutdown_needed() {
                            local instance_id=$1
                            local days=$2
                            local days_ago=$(date --date=-${days}days +%Y-%m-%d 2>/dev/null || date -v-${days}d +%Y-%m-%d)

                            aws ec2 describe-instances \
                                --region us-east-2 \
                                --output text \
                                --instance-ids $instance_id \
                                --query "Reservations[].Instances[?LaunchTime<='${days_ago}'][].InstanceId"
                        }

                        get_sir_state() {
                            local sir=$1

                            aws ec2 describe-spot-instance-requests \
                                --region us-east-2 \
                                --output text \
                                --query 'SpotInstanceRequests[].State' \
                                --spot-instance-request-ids $sir
                        }

                        main() {
                            echo -n > instances_to_terminate

                            aws ec2 describe-instances \
                                --region us-east-2 \
                                --output text \
                                --query 'Reservations[].Instances[].{
                                    AName:[Tags[?Key==`Name`].Value][0][0],
                                    InstanceId:InstanceId,
                                    RequestId:SpotInstanceRequestId,
                                    ZDays: [Tags[?Key==`stop-after-days`].Value][0][0]
                                }' \
                                --filter Name=instance-state-name,Values=running \
                                | sort -n \
                                | tee instances

                            while read -r name instance request days; do
                                state=$(get_sir_state "$request")
                                if [[ $state = cancelled ]]; then
                                    echo TERMINATE cancelled: $name
                                    echo ${instance} >> instances_to_terminate
                                fi
                                if [[ $days != None ]]; then
                                    if [[ -n $(is_shutdown_needed "${instance}" "${days}") ]]; then
                                        echo TERMINATE days: $name
                                        echo ${instance} >> instances_to_terminate
                                    else
                                        echo KEEP days: $name
                                    fi
                                fi
                            done < instances
                            cat instances_to_terminate
                            wc -l instances_to_terminate
                        }

                        main
                    '''
                }
                stash includes: 'instances_to_terminate', name: 'instances_to_terminate'

                script {
                    def instances_count = sh(returnStdout: true, script: '''
                        wc -l instances_to_terminate
                    ''').trim()
                    if (instances_count == '0 instances_to_terminate') {
                        echo "WARNING: everything ok, skip terminate"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Terminate instances') {
            steps {
                unstash 'instances_to_terminate'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        cat instances_to_terminate | xargs aws ec2 --region us-east-2 terminate-instances --instance-ids
                    '''
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: stop successful"
                } else if (currentBuild.result == 'UNSTABLE') {
                    echo 'everything ok'
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
        }
    }
}
