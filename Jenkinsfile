pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build') {
            steps {
                sh './gradlew --no-daemon clean assemble'
            }
        }
        stage('Test') {
            steps {
                sh './gradlew --no-daemon test'
            }
        }
        stage('Report') {
            steps {
                junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: false
                archiveArtifacts artifacts: '**/build/libs/*.jar', fingerprint: true, allowEmptyArchive: false
            }
        }
    }

    post {
        always {
            junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: true
            cleanWs()
        }
    }
}
