 

## docker service

sudo docker network create --driver bridge   cc-bridged-network
 

export DBHOST=bigtangle-mysql
export DB_PASSWORD=test1234

 
#sudo rm -fr /data/vm/$DBHOST/*
sudo  mkdir -p /data/vm/$DBHOST/var/lib/mysql
docker rm -f $DBHOST 

sudo docker run -d  -t --net=cc-bridged-network \
-v /data/vm/$DBHOST/var/lib/mysql:/var/lib/mysql  \
-e MYSQL_ROOT_PASSWORD=$DB_PASSWORD   \
-e MYSQL_DATABASE=info  --name=$DBHOST  -h $DBHOST   mysql:8.0.23 

 