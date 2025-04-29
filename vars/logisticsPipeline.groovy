def call() {
    pipeline {
        agent any

        stages {
            stage('testing') {
                steps {
                    echo 'Testing abc'
                }
            }
        }
    }
}
