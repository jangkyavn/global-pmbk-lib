def call() {
	pipeline {
		stages {
			stage('testing') {
				steps {
					echo 'Testing abc'
				}
			}
		}
	}
}