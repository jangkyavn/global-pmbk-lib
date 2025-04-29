def call() {
    pipeline {
		agent any
		environment {
			ADDRESS_1 = 'qlvt-anh1.local'
			ADDRESS_2 = 'qlvt-anh2.local'
		}
		
		tools {
			msbuild 'MSBuild'
		}
		
		stages {
			stage('Prepare directories') {
				steps {
					// Tạo thư mục cho FE và BE nếu chưa tồn tại
					powershell '''
						if (-not (Test-Path "Frontend")) {
							New-Item -ItemType Directory -Path "Frontend"
						}
						if (-not (Test-Path "Backend")) {
							New-Item -ItemType Directory -Path "Backend"
						}
					'''
				}
				post {
					success {
						echo 'Prepare directories SUCCESS'
					}
					failure {
						echo 'Prepare directories FAILURE'
					}
				}
			}
			
			stage('Checkout branch') {
				steps {
					script {
						def branches = [:]
	  
						if (params.BUILD_OPTION == 'Frontend' || params.BUILD_OPTION == 'Both') {
							branches['Frontend'] = {
								dir('Frontend') {  // Chạy lệnh git bên trong thư mục Frontend
									git branch: "${env.BRANCH_NAME}",
									credentialsId: "${env.GIT_CRED_ID}",
									url: "${env.GIT_FE_LOGISTICS_URL}"
								}
							}
						}
						
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							branches['Backend'] = {
								dir('Backend') {  // Chạy lệnh git bên trong thư mục Backend
									git branch: "${env.BRANCH_NAME}",
									credentialsId: "${env.GIT_CRED_ID}",
									url: "${env.GIT_BE_LOGISTICS_URL}"
								}
							}
						}
			
						parallel branches  // Thực hiện checkout đồng thời
					}
				}
				post {
					success {
						echo 'Checkout branch SUCCESS'
					}
					failure {
						echo 'Checkout branch FAILURE'
					}
				}
			}
			
			stage('Build project') {
				steps {
					script {
						def branches = [:]
						
						echo params.BUILD_OPTION
						
						if (params.BUILD_OPTION == 'Frontend' || params.BUILD_OPTION == 'Both') {
							branches['Frontend'] = {
								dir('Frontend') {  // Chạy lệnh git bên trong thư mục Frontend
									// Không có
								}
							}
						}
						
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							branches['Backend'] = {
								dir('Backend') {  // Chạy lệnh git bên trong thư mục Backend
									bat '''
										"%NUGET_EXE%" restore WebAPI.sln
										msbuild WebAPI.sln /p:Configuration=Release
										exit 0
									'''
								}
							}
						}
			
						parallel branches  // Thực hiện checkout đồng thời
					}
				}
				post {
					success {
						echo 'Build project SUCCESS'
					}
					failure {
						echo 'Build project FAILURE'
					}
				}
			}
			
			stage('Publish project') {
				steps {
					script {
						def branches = [:]
			
						if (params.BUILD_OPTION == 'Frontend' || params.BUILD_OPTION == 'Both') {
							branches['Frontend'] = {
								dir('Frontend') {  
									powershell '''
										npm install
										
										# Đường dẫn thư mục
										$prePublishPath = "./pre_dist"
										$publishPath = "./dist"
			
										# Nếu prePublishPath là file, xóa đi và tạo lại thư mục
										if (Test-Path -Path $prePublishPath -PathType Leaf) {
											Remove-Item -Force $prePublishPath
										}
										
										if (!(Test-Path -Path $prePublishPath)) {
											New-Item -ItemType Directory -Path $prePublishPath | Out-Null
										} else {
											# Nếu đã tồn tại, xóa toàn bộ nội dung bên trong nhưng giữ thư mục
											Remove-Item -Path "$prePublishPath\\*" -Recurse -Force
										}
			
										# Sao lưu bản build cũ trước khi xóa
										if (Test-Path -Path $publishPath) {
											Copy-Item -Path "$publishPath\\*" -Destination $prePublishPath -Recurse -Force
											Remove-Item -Recurse -Force $publishPath
										}
			
										# Build lại FE
										npm run build --configuration=production
									'''
								}
							}
						}
			
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							branches['Backend'] = {
								dir('Backend') {  
									powershell '''
										# Đường dẫn thư mục
										$prePublishPath = "./PrePublish"
										$publishPath = "./Publish"
			
										# Nếu prePublishPath là file, xóa đi và tạo lại thư mục
										if (Test-Path -Path $prePublishPath -PathType Leaf) {
											Remove-Item -Force $prePublishPath
										}
										
										if (!(Test-Path -Path $prePublishPath)) {
											New-Item -ItemType Directory -Path $prePublishPath | Out-Null
										} else {
											# Nếu đã tồn tại, xóa toàn bộ nội dung bên trong nhưng giữ thư mục
											Remove-Item -Path "$prePublishPath\\*" -Recurse -Force
										}
			
										# Sao lưu bản build cũ trước khi xóa
										if (Test-Path -Path $publishPath) {
											Copy-Item -Path "$publishPath\\*" -Destination $prePublishPath -Recurse -Force
											Remove-Item -Recurse -Force $publishPath
										}
			
										# Build lại BE và publish
										dotnet publish -c Release -o $publishPath
										exit 0
									'''
								}
							}
						}
			
						parallel branches  
					}
				}
				post {
					success {
						echo 'Publish project SUCCESS'
					}
					failure {
						echo 'Publish project FAILURE'
					}
				}
			}

			stage('Deploy Blue to IIS') { 
				steps {
					script {
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							dir('Backend') {  // Chạy lệnh git bên trong thư mục Backend
								powershell '''
									$deployBasePath = "${env:DEPLOY_BASE_PATH}"
									$domain = "${env:JOB_NAME}"
									$address = "${env:ADDRESS_2}"
					
									$apiSource1Path = "$deployBasePath\\$domain\\webs\\$address"
	  
									$appPoolState = (Get-WebAppPoolState -Name $address).Value
									$websiteState = (Get-Website -Name $address).State
									
									if ($appPoolState -eq "Started") {
										Stop-WebAppPool -Name $address
									} else {
										Write-Host "Application Pool $address is already stopped."
									}
									
									if ($websiteState -eq "Started") {
										Stop-Website -Name $address
									} else {
										Write-Host "Website $address is already stopped."
									}
				
									Start-Sleep -Seconds 5
									
									robocopy "PrePublish" $apiSource1Path /E /Z /NP /XF "web.config" "appsettings.json"
									
									Start-WebAppPool -Name $address
									Start-Website -Name $address

									exit 0
								'''
							}
						}
						
						if (params.BUILD_OPTION == 'Frontend' || params.BUILD_OPTION == 'Both') {
							dir('Frontend') {  // Chạy lệnh git bên trong thư mục Frontend
								powershell '''
									$deployBasePath = "${env:DEPLOY_BASE_PATH}"
									$domain = "${env:JOB_NAME}"
									$address = "${env:ADDRESS_2}"
									
									$clientAppPath = "$deployBasePath\\$domain\\webs\\$address\\ClientApp"
									
									if (-not (Test-Path -Path $clientAppPath -PathType Container)) {
										New-Item -ItemType Directory -Path $clientAppPath | Out-Null
									} else {
										Write-Host "ClientApp directory already exists at $clientAppPath"
									}
									
									robocopy "pre_dist" $clientAppPath /E /Z /NP /XF "env.js"

									exit 0
								'''
							}
						}
					}
				}
				post {
					success {
						echo 'Deploy Blue to IIS SUCCESS'
					}
					failure {
						echo 'Deploy Blue to IIS FAILURE'
					}
				}
			}
			
			stage('Switch traffic to Blue') {
				steps {
					powershell '''
						$deployBasePath = "${env:DEPLOY_BASE_PATH}"
						$domain = "${env:JOB_NAME}"
						$configPath = "$deployBasePath\\$domain\\appsettings.json"
						
						# Đọc nội dung file
						$content = Get-Content -Path $configPath -Raw
						
						# Thay thế "ClusterId": "greenCluster" thành "ClusterId": "blueCluster"
						$updatedContent = $content -replace '"ClusterId": "greenCluster"', '"ClusterId": "blueCluster"'
						
						# Ghi lại nội dung đã cập nhật
						$updatedContent | Set-Content -Path $configPath -Encoding UTF8
						
						# Gửi HTTP request để reload cấu hình
						Invoke-RestMethod -Uri "https://$domain/reload-config" -Method Post
					'''
				}
				post {
					success {
						echo 'Switch traffic to Blue SUCCESS'
						powershell 'Start-Sleep -Seconds 20'
					}
					failure {
						echo 'Switch traffic to Blue FAILURE'
					}
				}
			}
			
			stage('Deploy Green to IIS') {
				steps {
					script {
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							dir('Backend') {  // Chạy lệnh git bên trong thư mục Backend
								powershell '''
									$deployBasePath = "${env:DEPLOY_BASE_PATH}"
									$domain = "${env:JOB_NAME}"
									$address = "${env:ADDRESS_1}"
					
									$apiSource1Path = "$deployBasePath\\$domain\\webs\\$address"
	  
									$appPoolState = (Get-WebAppPoolState -Name $address).Value
									$websiteState = (Get-Website -Name $address).State
									
									if ($appPoolState -eq "Started") {
										Stop-WebAppPool -Name $address
									} else {
										Write-Host "Application Pool $address is already stopped."
									}
									
									if ($websiteState -eq "Started") {
										Stop-Website -Name $address
									} else {
										Write-Host "Website $address is already stopped."
									}
				
									Start-Sleep -Seconds 5
									
									robocopy "Publish" $apiSource1Path /E /Z /NP /XF "web.config" "appsettings.json"
									
									Start-WebAppPool -Name $address
									Start-Website -Name $address

									exit 0
								'''
							}
						}
						
						if (params.BUILD_OPTION == 'Frontend' || params.BUILD_OPTION == 'Both') {
							dir('Frontend') {  // Chạy lệnh git bên trong thư mục Frontend
								powershell '''
									$deployBasePath = "${env:DEPLOY_BASE_PATH}"
									$domain = "${env:JOB_NAME}"
									$address = "${env:ADDRESS_1}"
									
									$clientAppPath = "$deployBasePath\\$domain\\webs\\$address\\ClientApp"
									
									if (-not (Test-Path -Path $clientAppPath -PathType Container)) {
										New-Item -ItemType Directory -Path $clientAppPath | Out-Null
									} else {
										Write-Host "ClientApp directory already exists at $clientAppPath"
									}
									
									robocopy "dist" $clientAppPath /E /Z /NP /XF "env.js"

									exit 0
								'''
							}
						}
					}
				}
				post {
					success {
						echo 'Deploy Green to IIS SUCCESS'
					}
					failure {
						echo 'Deploy Green to IIS FAILURE'
					}
				}
			}
			
			stage('Detect migration') {
				steps {
					script {
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							powershell '''
								$address = "${env:ADDRESS_1}"
								$apiUrl = "http://$address/api/Database/GetPendingMigrations"
								$headers = @{ "x-api-key" = "20f8326ef78d41ca80ec3d9a87537cce" }
								$response = Invoke-RestMethod -Uri $apiUrl -Method Get -Headers $headers
								$response | ConvertTo-Json -Depth 10 | Out-File pending_migrations.txt
							'''
						}
					}
				}
				post {
					success {
						echo 'Detect migration SUCCESS'
					}
					failure {
						echo 'Detect migration FAILURE'
					}
				}
			}
			
			stage('Migrate DB') {
				steps {
					script {
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							powershell '''
								$address = "${env:ADDRESS_1}"
								$apiUrl = "http://$address/api/Database/UpdateDatabase/1"
								$headers = @{ "x-api-key" = "20f8326ef78d41ca80ec3d9a87537cce" }
								Invoke-RestMethod -Uri $apiUrl -Method Get -Headers $headers
							'''
						}
					}
				}
				post {
					success {
						echo 'Migrate DB SUCCESS'
					}
					failure {
						echo 'Migrate DB FAILURE'
					}
				}
			}
			
			stage('Run sql scripts') {
				steps {
					script {
						if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
							powershell '''
								$address = "${env:ADDRESS_1}"
								$apiUrl = "http://$address/api/Database/RunSqlScripts"
								$headers = @{ "x-api-key" = "20f8326ef78d41ca80ec3d9a87537cce" }
								Invoke-RestMethod -Uri $apiUrl -Method Get -Headers $headers
							'''
						}
					}
				}
				post {
					success {
						echo 'Run sql scripts SUCCESS'
					}
					failure {
						echo 'Run sql scripts FAILURE'
					}
				}
			}
			
			stage('Zip files') {
				steps {
					script {
						dir('Backend') {  // Chạy lệnh git bên trong thư mục Backend
							powershell '''
								$domain = "${env:JOB_NAME}"
								$buildLogPath = "${env:BUILD_LOG_PATH}"
								
								$buildLogDetailPath = "$buildLogPath\\$domain\\$domain"
								
								if (!(Test-Path -Path $buildLogDetailPath)) {
									New-Item -ItemType Directory -Path $buildLogDetailPath | Out-Null
								}
							  
								robocopy "Publish" $buildLogDetailPath /E /Z /NP /XF "web.config" "appsettings.json"
							
								exit 0
							'''
						}
						
						dir('Frontend') {  // Chạy lệnh git bên trong thư mục Frontend
							powershell '''
								$domain = "${env:JOB_NAME}"
								$buildLogPath = "${env:BUILD_LOG_PATH}"
								
								$buildLogDetailPath = "$buildLogPath\\$domain\\$domain\\ClientApp"
								
								if (!(Test-Path -Path $buildLogDetailPath)) {
									New-Item -ItemType Directory -Path $buildLogDetailPath | Out-Null
								}

								robocopy "dist" $buildLogDetailPath /E /Z /NP /XF "env.js"
						
								exit 0
							'''
						}
					}
				}
				post {
					success {
						powershell '''
							$domain = "${env:JOB_NAME}"
							$buildNumber = "#${env:BUILD_NUMBER}"
							$buildLogPath = "${env:BUILD_LOG_PATH}"
							
							$sourcePath = "$buildLogPath\\$domain\\$domain"
							$zipFileName = "$buildNumber.zip"
							$zipFilePath = "$buildLogPath\\$domain\\$zipFileName"
							
							Compress-Archive -Path "$sourcePath\\*" -DestinationPath $zipFilePath -Force
							
							# Xóa thư mục sau khi nén xong
							if (Test-Path $zipFilePath) {
								Remove-Item -Recurse -Force $sourcePath
							} else {
								Write-Output "Deleted folder path: $($zipFilePath) after zipped"
							}
							
							# Đường dẫn thư mục chứa file ZIP
							$zipFolder = "$buildLogPath\\$domain"
							
							# Lấy danh sách các file ZIP, sắp xếp theo ngày tạo (mới nhất -> cũ nhất)
							$zipFiles = Get-ChildItem -Path $zipFolder -Filter "*.zip" | Sort-Object LastWriteTime -Descending
							
							# Kiểm tra nếu số lượng file ZIP > 10 thì xóa các file cũ hơn
							if ($zipFiles.Count -gt 10) {
								$filesToDelete = $zipFiles[10..$zipFiles.Count]  # Lấy danh sách file thừa
							
								foreach ($file in $filesToDelete) {
									Remove-Item -Path $file.FullName -Force
									Write-Output "Deleted file: $($file.Name)"
								}
							} else {
								Write-Output "Total count current file zip: $($zipFiles.Count), No need to delete!"
							}
						'''
						
						echo 'Zip files SUCCESS'
					}
					failure {
						echo 'Zip files FAILURE'
					}
				}
			}
			
			stage('Switch traffic to Green') {
				steps {
					powershell '''
						$deployBasePath = "${env:DEPLOY_BASE_PATH}"
						$domain = "${env:JOB_NAME}"
						$configPath = "$deployBasePath\\$domain\\appsettings.json"
						
						# Đọc nội dung file
						$content = Get-Content -Path $configPath -Raw
			
						# Thay thế "ClusterId": "blueCluster" thành "ClusterId": "greenCluster"
						$updatedContent = $content -replace '"ClusterId": "blueCluster"', '"ClusterId": "greenCluster"'
						
						# Ghi lại nội dung đã cập nhật
						$updatedContent | Set-Content -Path $configPath -Encoding UTF8
						
						# Gửi HTTP request để reload cấu hình
						Invoke-RestMethod -Uri "https://$domain/reload-config" -Method Post
					'''
				}
				post {
					success {
						echo 'Switch traffic to Green SUCCESS'
					}
					failure {
						echo 'Switch traffic to Green FAILURE'
					}
				}
			}
		}
		
		post {
			success {
				script {
					def message = "[${env.BUILD_USER}] built SUCCESS! ✅\n" +
								"Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}\n" +
								"Branch: ${env.BRANCH_NAME} ${env.BUILD_OPTION}\n"
				
					if (params.BUILD_OPTION == 'Backend' || params.BUILD_OPTION == 'Both') {
						 // Đọc danh sách từ file
						def migrations = powershell(returnStdout: true, script: 'Get-Content pending_migrations.txt').trim()
					
						if (migrations) {
							message += "Migration: $migrations"
						} else {
							message += "Migration: nothing"
						}
					}
					
					powershell """
						Invoke-RestMethod -Uri '${env.TELE_SEND_MESSAGE_API}' -Method Post -Body @{ chat_id='-4121211818'; text='${message}' }
					"""
				}
			}
			failure {
				script {
					def message = "[${env.BUILD_USER}] built FAILURE! ❌\n" +
								"Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}\n" +
								"Branch: ${env.BRANCH_NAME} ${env.BUILD_OPTION}\n"
					
					powershell """
						Invoke-RestMethod -Uri '${env.TELE_SEND_MESSAGE_API}' -Method Post -Body @{ chat_id='-4121211818'; text='${message}' }
					"""
				}
			}
		}
	}
}
