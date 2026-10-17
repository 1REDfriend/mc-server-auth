# Starts Mailpit, a fake mail server that catches every email locally.
# SMTP: 127.0.0.1:41853   Web inbox: http://127.0.0.1:41854
$ErrorActionPreference = 'Stop'
$name = 'mc-auth-mailpit'
$existing = docker ps -a --filter "name=^$name$" --format '{{.Names}}'
if ($existing -eq $name) {
    docker start $name | Out-Null
} else {
    docker run -d --name $name -p 127.0.0.1:41853:1025 -p 127.0.0.1:41854:8025 axllent/mailpit:v1.31.6 | Out-Null
}
Write-Host 'Mailpit running. Inbox: http://127.0.0.1:41854'
