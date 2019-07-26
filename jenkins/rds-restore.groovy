properties([  
    parameters([ 
      [$class: 'CascadeChoiceParameter',   
            choiceType: 'PT_SINGLE_SELECT',   
            description: 'repository name',   
            filterLength: 1,   
            filterable: false,   
            name: 'rds_selected',   
            randomName: 'choice-parameter-1235631314456221312',   
            referencedParameters: '',   
            script: [  
                $class: 'GroovyScript',   
                fallbackScript: [  
                    classpath: [],   
                    sandbox: false,   
                    script:   
                        'return[\'Erro\']'  
                ],   
                script: [  
                    classpath: [],   
                    sandbox: false,   
                    script:   
                        '''   
                          def gettags = ("aws rds describe-db-instances --region us-east-1 --query DBInstances[].DBInstanceIdentifier --output text").execute()  
                          def list_repo = gettags.text.split()    

                          return ["sample-database"]  
                        '''  
                ]  
            ]  
        ]  
    ])   
  ]) 

pipeline {  
  agent any  
 
  stages {  
    stage('Running restore') {  
      steps {  
        script {  
            echo "Getting latest snapshot RDS ${params.rds_selected}"
            
            def string_script="aws rds describe-db-snapshots --region us-east-1 --db-instance-identifier=${params.rds_selected} --query='reverse(sort_by(DBSnapshots, &SnapshotCreateTime))[0]|DBSnapshotArn'" 
            
            LATEST_SNAPSHOT = sh (
                script: string_script,
                returnStdout: true
            ).trim().replaceAll('"', "")
            
            echo "Latest snapshot to RDS ${params.rds_selected} is ${LATEST_SNAPSHOT}."


            echo "Deleting ${params.rds_selected}-restore db instance!"
            sh ('#!/bin/sh -e\n' + "aws rds delete-db-instance --region us-east-1 --db-instance-identifier ${params.rds_selected}-restore --final-db-snapshot-identifier final-snapshot-jenkins-${env.BUILD_ID}")
            
            def get_restore_state="aws rds describe-db-instances --region us-east-1 --query DBInstances[].DBInstanceStatus --filters Name=db-instance-id,Values=${params.rds_selected}-restore --output text" 
            
            def RESTORE_DB_STATE = sh (
              script: get_restore_state,
              returnStdout: true
            ).trim()

            while(RESTORE_DB_STATE == "deleting"){
              RESTORE_DB_STATE = sh (
                script: get_restore_state,
                returnStdout: true
              ).trim()
              
              sleep(time:10,unit:"SECONDS")
              echo "Database ${params.rds_selected}-restore is ${RESTORE_DB_STATE}"
            }

            echo "Database ${params.rds_selected}-restore is ${RESTORE_DB_STATE}"

            echo "Creating ${params.rds_selected}-restore database with ${LATEST_SNAPSHOT} snapshot!"
            sh ('#!/bin/sh -e\n' + "aws rds restore-db-instance-from-db-snapshot --region us-east-1 --db-instance-identifier ${params.rds_selected}-restore --db-snapshot-identifier ${LATEST_SNAPSHOT}")

            def get_recreated_state="aws rds describe-db-instances --region us-east-1 --query DBInstances[].DBInstanceStatus --filters Name=db-instance-id,Values=${params.rds_selected}-restore --output text" 
            
            def RECREATED_DB_STATE = sh (
              script: get_recreated_state,
              returnStdout: true
            ).trim()

            while(RECREATED_DB_STATE == "creating"){ //enquanto o RDS não foi apagado
              RECREATED_DB_STATE = sh (
                script: get_recreated_state,
                returnStdout: true
              ).trim()

              echo "Database ${params.rds_selected}-restore is ${RECREATED_DB_STATE}"
            }

            echo "Database ${params.rds_selected}-restore RESTORED with ${LATEST_SNAPSHOT}"
            
        }  
      }  
    }      
                
    stage('Testing connection target database!') {   
      steps {  
        script {
          
          echo "get RDS Endpoint"

          ENDPOINT_RDS = sh (
              script: "aws rds describe-db-instances --region us-east-1 --query DBInstances[].Endpoint.Address --filters Name=db-instance-id,Values=${params.rds_selected}-restore --output text",
              returnStdout: true
          ).trim()

          echo "nc -zv ${ENDPOINT_RDS} 3306"
        }
    }  

    post { 
        success { 
          echo "Restore do database ${params.rds_selected} realizado com sucesso!" 
        } 
        failure { 
          echo "Houve algo errado no restore do database ${params.rds_selected} :(" 
        } 
      } 
    }  
  }  
    
  post {  
    always {  
      cleanWs()  
    }  
  }  
}