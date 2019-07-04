properties([  
    parameters([ 
      [$class: 'CascadeChoiceParameter',   
            choiceType: 'PT_SINGLE_SELECT',   
            description: 'repository name',   
            filterLength: 1,   
            filterable: true,   
            name: 'repo_selected',   
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
                          def gettags = ("aws codecommit list-repositories --output text").execute()  
                          def list_repo = gettags.text.readLines().collect {   
                            it.split("\t")[2] 
                          }    
                          return list_repo.findAll { it -> it.startsWith('filter') }  
                        '''  
                ]  
            ]  
        ],  
        [$class: 'CascadeChoiceParameter',   
            choiceType: 'PT_SINGLE_SELECT',   
            description: 'branch name',   
            filterLength: 1,   
            filterable: true,   
            name: 'branch_selected',   
            randomName: 'choice-parameter-5631314456178619',   
            referencedParameters: 'repo_selected',   
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
                          def gettags = ("git ls-remote -t -h ssh://${ACCOUNT_ID}@git-codecommit.us-east-1.amazonaws.com/v1/repos/${repo_selected}").execute()  
                          return gettags.text.readLines().collect {   
                            it.split()[1].replaceAll("refs/heads/", "").replaceAll("refs/tags/", "").replaceAll("\\\\^\\\\{\\\\}", "")  
                          }  
                        '''  
                ]  
            ]  
        ], 
        [$class: 'CascadeChoiceParameter',   
            choiceType: 'PT_SINGLE_SELECT',   
            description: '',   
            filterLength: 1,   
            filterable: false,   
            name: 'cluster_selected',   
            randomName: 'choice-parameter-5631312213123',     
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
                          def gettags = ("aws ecs list-clusters --output text").execute()  
                          return gettags.text.readLines().collect {   
                            it.split()[1].split('/')[1] 
                          }  
                        '''  
                ]  
            ]  
        ], 
        [$class: 'CascadeChoiceParameter',   
            choiceType: 'PT_SINGLE_SELECT',   
            description: '',   
            filterLength: 1,   
            filterable: false,   
            name: 'service_selected',   
            randomName: 'choice-parameter-5631312213213213123',   
            referencedParameters: 'cluster_selected',   
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
                          def gettags = ("aws ecs list-services --cluster ${cluster_selected} --output text").execute()  
 
                          def list_services = gettags.text.readLines().collect {   
                              it.split()[1].split('/')[1]                                                   
                          }  
 
                          def list_service_container = list_services.collect { 
 
                                def task_arn = ("aws ecs list-tasks --cluster ${cluster_selected} --service-name ${it} --query taskArns[0] --output text").execute().text   
                                def container_name = ("aws ecs describe-tasks --cluster ${cluster_selected} --tasks ${task_arn} --query tasks[0].containers[0].name --output text").execute().text 
                           
                                it = it + "/" + container_name      
                               
                          } 
                          return list_service_container 
                        '''  
                ]  
            ]  
        ]  
    ])   
  ])  
pipeline {  
  agent any  
 
  stages {  
    stage('Define ECS Variables') {  
      steps {  
        script {  
            service = params.service_selected.split('/')[0] 
            container_name =  params.service_selected.split('/')[1].replaceAll('\n', '') 
            container_tag_name = params.service_selected.split('/')[1].split('-')[1].replaceAll('\n', '') 
            image_tag = params.cluster_selected.split('-')[1] + '-' 
 
            echo "Deploying... \n\nRepository: ${params.repo_selected} \nBranch: ${params.branch_selected} \nCluster: ${params.cluster_selected} \nService: ${service} \nContainer: ${container_name}"  
        }  
      }  
    }      
        
    stage('Clone Repository') {  
      steps {  
        git branch: "${params.branch_selected}",  
        credentialsId: 'bdc7ff58-8551-425c-8ee6-2b16afd8cff5',  
        url: "ssh://${ACCOUNT_ID}@git-codecommit.us-east-1.amazonaws.com/v1/repos/${params.repo_selected}"  
      }  
    }  
        
    stage('Docker Build') {  
      steps { 
 
        //sh "echo eval \$(aws ecr get-login --no-include-email | sed 's|https://||')" 
        sh "\$(aws ecr get-login --no-include-email)" 
        sh "/usr/bin/mvn clean install" 
        sh "docker build -t ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}${env.BUILD_NUMBER} ." 
        sh "docker push ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}${env.BUILD_NUMBER}" 
        sh "docker tag ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}${env.BUILD_NUMBER} ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}latest" 
        sh "docker push ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}latest" 
      } 
    } 
 
    stage('ECS Deploy new Task Definition') {   
      steps {  
        sh "/usr/local/bin/ecs deploy ${params.cluster_selected} ${service} --image ${container_name} ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${container_tag_name}:${image_tag}${env.BUILD_NUMBER} --timeout 1200"  
      }  
    post { 
        success { 
          echo "Deploy Realizado" 
        } 
        failure { 
          echo "Triggando o Rollback" 
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
 