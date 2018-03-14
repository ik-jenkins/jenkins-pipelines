library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
        choice(
            choices: 'testing\nlaboratory',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                installDocker()
                installCentosGit()

                git poll: true, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'
                sh '''
                    git reset --hard
                    git clean -xdf
                    git submodule update --remote --init --recommend-shallow --jobs 10

                    git rev-parse HEAD         > gitCommit
                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}" > uploadPath
                '''
                archiveArtifacts 'uploadPath'
                stash includes: 'gitCommit,shortCommit', name: 'gitCommit'
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build source tarball') {
            steps {
                sh '''
                    sg docker -c "
                        export pmm_version=$(cat VERSION)
                        ./build/bin/build-client-source
                    "
                '''
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build binary tarball') {
            steps {
                sh '''
                    sg docker -c "
                        export pmm_version=$(cat VERSION)
                        ./build/bin/build-client-binary
                    "
                '''
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }

        stage('Build source rpm') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-srpm centos:6"'
                stash includes: 'results/srpm/pmm-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build binary rpms') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-rpm centos:6"'
                sh 'sg docker -c "./build/bin/build-client-rpm centos:7"'
                sh 'sudo chown -R $(id -u):$(id -g) results'
                stash includes: 'results/rpm/pmm-client-*.rpm', name: 'rpms'
                uploadRPM()
            }
        }

        stage('Build source deb') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-sdeb debian:wheezy"'
                stash includes: 'results/source_deb/*', name: 'debs'
                uploadDEB()
            }
        }
        stage('Build binary debs') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-deb debian:jessie"'
                sh 'sg docker -c "./build/bin/build-client-deb debian:stretch"'
                sh 'sg docker -c "./build/bin/build-client-deb debian:wheezy"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:artful"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:bionic"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:trusty"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:xenial"'
                sh 'sudo chown -R $(id -u):$(id -g) results'
                stash includes: 'results/deb/*.deb', name: 'debs'
                uploadDEB()
            }
        }
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                sync2Prod(DESTINATION)
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build ${currentBuild.result}"
                }
            }
        }
    }
}