 

## docker service

#sudo docker network create --driver bridge   cc-bridged-network
 

export DBHOST=bigtangle-postgresql
export DB_PASSWORD=test1234

 
#sudo rm -fr /data/vm/$DBHOST/*  
sudo  mkdir -p /data/vm/$DBHOST/var/lib/postgresql/data
docker rm -f $DBHOST 

 
#psql -h localhost -p 5432 -U root -d info

 sudo docker run -d \
      --name $DBHOST \
      -e POSTGRES_USER=root \
      -e POSTGRES_PASSWORD=test1234 \
      -e POSTGRES_DB=info \
      -p 5432:5432 \
      -v /data/vm/$DBHOST/var/lib/postgresql/data:/var/lib/postgresql/data \
      postgres:latest