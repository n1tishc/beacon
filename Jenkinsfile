// The same checks as .github/workflows/ci.yml, for teams that run Jenkins. It builds and tests only;
// deploying stays with the GitHub Actions deploy job, which holds the AWS role (OIDC) and the
// `production` environment, so there is one way to ship and one set of credentials.
//
// Agent requirements: Docker with Compose v2, JDK 21, curl and jq. Node, Terraform and Playwright run
// in containers (Docker Pipeline plugin). The backend runs on the agent itself because Testcontainers
// needs the agent's Docker daemon.
pipeline {
  agent any

  options {
    timeout(time: 60, unit: 'MINUTES')
    // The stack publishes fixed host ports (4200, 4566, ...), so one build per agent at a time.
    disableConcurrentBuilds(abortPrevious: true)
    timestamps()
  }

  environment {
    // The containers run as the Jenkins user, whose home is not writable inside them.
    npm_config_cache = "${WORKSPACE}/.npm"
  }

  stages {
    stage('Build and test') {
      parallel {
        stage('Backend') {
          steps {
            dir('backend') {
              // Unit and integration tests, JaCoCo reports, and the coverage gate.
              sh './mvnw -B verify'
            }
          }
          post {
            always {
              junit testResults: 'backend/*/target/surefire-reports/*.xml', allowEmptyResults: true
              archiveArtifacts artifacts: 'backend/*/target/site/jacoco/**', allowEmptyArchive: true
            }
          }
        }

        stage('Frontend') {
          agent {
            docker {
              image 'node:22'
              reuseNode true
            }
          }
          steps {
            dir('frontend') {
              sh 'npm ci --no-audit --no-fund'
              sh 'npm run test:ci'
              sh 'npm run build'
            }
          }
        }

        stage('Terraform') {
          agent {
            docker {
              image 'hashicorp/terraform:1.15'
              args '--entrypoint='
              reuseNode true
            }
          }
          steps {
            dir('infra/terraform') {
              sh 'terraform fmt -check -recursive'
              sh 'cd bootstrap && terraform init -backend=false -input=false && terraform validate'
              sh 'terraform init -backend=false -input=false'
              sh 'terraform validate'
              sh 'terraform test'
            }
          }
        }
      }
    }

    stage('Stack') {
      steps {
        sh 'docker compose up --build --detach --wait --wait-timeout 360'
      }
    }

    stage('Smoke test') {
      steps {
        sh 'scripts/smoke-test.sh http://localhost:4200'
      }
    }

    // After the smoke test, so the alert Lambda is already warm.
    stage('E2E (Playwright)') {
      agent {
        docker {
          image 'mcr.microsoft.com/playwright:v1.64.0-noble'
          // The browser has to reach the stack at http://localhost:4200, the origin S3's CORS rule allows.
          args '--network host'
          reuseNode true
        }
      }
      environment {
        CI = 'true'
      }
      steps {
        dir('e2e') {
          sh 'npm ci --no-audit --no-fund'
          sh 'npx playwright test'
        }
      }
      post {
        always {
          archiveArtifacts artifacts: 'e2e/playwright-report/**, e2e/test-results/**', allowEmptyArchive: true
        }
      }
    }

    stage('Smoke test with Slack down') {
      steps {
        sh 'docker compose stop fake-slack'
        sh 'EXPECT_SLACK=down scripts/smoke-test.sh http://localhost:4200'
      }
    }
  }

  post {
    failure {
      sh 'docker compose logs --no-color > compose.log || true'
      archiveArtifacts artifacts: 'compose.log', allowEmptyArchive: true
    }
    always {
      sh 'docker compose down --volumes || true'
    }
  }
}
