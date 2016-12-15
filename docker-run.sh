#!/bin/bash
docker run -d --name ardoq-docker-compose-addon -p 9009:9009 -e "ARDOQ_API_URL=http://localhost:8080" -e "ARDOQ_WEB_URL=http://localhost:8080" ardoq/ardoq-docker-compose-addon:latest
