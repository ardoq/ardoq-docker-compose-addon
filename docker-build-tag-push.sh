#!/bin/bash
confirm () {
    read -r -p "${1:-Are you sure? [y/N]} " response
    case $response in
        [yY][eE][sS]|[yY])
            true
            ;;
        *)
            false
            ;;
    esac
}

FROM_IMAGE=$(grep FROM Dockerfile|cut -d' ' -f2)
docker run --rm -v ~/.m2/repository:/root/.m2/repository -v $(pwd):/build -w /build -e "LEIN_ROOT=true" $FROM_IMAGE lein uberjar

VERSION=$(grep "ENV VERSION" Dockerfile|cut -d' ' -f3)

#TAG="ardoq/ardoq-docker-compose-addon:$VERSION"
TAG="ardoq/ardoq-docker-compose-addon:latest"

echo "building $TAG"
docker build -t $TAG .
confirm "Push $TAG to DockerHub? [y/N]" && docker push $TAG



