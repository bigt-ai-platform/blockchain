 

## docker service

docker network create --driver bridge   bigtangle-bridged-network
 $(date '+%Y-%m-%d')
export BIGTANGLEVERSION=2021-12-20
export DBHOST=bigtangle-backup-mysql
export SERVERHOST=bigtangle-backup
export KAFKA=bigtangle.de:9092
export SERVER_MINERADDRESS=1CWxNAAAmTVRqodSSXTatxSopKEAD9EJw8
export REQUESTER=https://bigtangle.de:8088
export TOPIC_OUT_NAME=bigtangle
export SERVER_NET=Mainnet
export SSL=true
export KEYSTORE=/app/bigtangle-server/ca.pkcs12
export SERVICE_MINING=false
export DB_PASSWORD=test1234
export SERVICE_MINING_RATE=15000
export SERVERPORT=18088
 
# rm -fr /data/vm/$DBHOST/*
 
docker rm -f $DBHOST 

docker run -d  -t      -p 3306:3306  \
-v /data/vm/$DBHOST/var/lib/mysql:/var/lib/mysql  \
-e MYSQL_ROOT_PASSWORD=$DB_PASSWORD   \
-e MYSQL_DATABASE=info  --name=$DBHOST  -h $DBHOST   mysql:8.0.23 


docker rm -f $SERVERHOST 

docker  run -d -t --link  $DBHOST \
  -v /data/vm/$SERVERHOST:/data/vm  -v /var/run/docker.sock:/var/run/docker.sock   \
-p $SERVERPORT:8088 --name  $SERVERHOST -e DOCKERCREATEDBHOST=true  -e CHECKPOINT=500000 \
-e DB_PASSWORD=$DB_PASSWORD -e SERVER_PORT=8088  -e DB_NAME=info \
-e DB_HOSTNAME=$DBHOST -e DOCKERDBHOST=$DBHOST -e SERVICE_MCMC_RATE=1000 \
-e SERVER_MINERADDRESS=$SERVER_MINERADDRESS -e SERVERMODE= \
-e BOOT_STRAP_SERVERS=$KAFKA    -e TOPIC_OUT_NAME=$TOPIC_OUT_NAME \
-e CONSUMERIDSUFFIX=$SERVERHOST.$HOSTNAME \
-e REQUESTER=$REQUESTER -e CHECKPOINT=$CHECKPOINT \
-e SERVICE_MINING=$SERVICE_MINING -e SERVICE_MCMC=true \
-e SERVER_NET=$SERVER_NET -e SSL=$SSL -e KEYSTORE=$KEYSTORE \
-h $SERVERHOST  j0904cui/bigtangle:$BIGTANGLEVERSION

 
 docker logs -f bigtangle-backup
sleep 10s
docker exec  bigtangle-backup /bin/sh -c " tail -f /logs/server.log"
docker exec  bigtangle-backup /bin/sh -c " tail -f /var/log/supervisor/serverstart-stdout*"

# http://jpetazzo.github.io/2015/09/03/do-not-use-docker-in-docker-for-ci/

 rm -fr /data/vm/bigtangle-backup-mysql/var/lib/mysql/binlog.*
 
 cp -r    /data/vm/bigtangle-mysql/var/lib/mysql/  /data/vm/bigtangle-backup-mysql/var/lib/mysql/
 sudo rm -fr /data/vm/bigtangle-backup-mysql/var/lib
 mkdir -p /data/vm/bigtangle-backup-mysql/var/lib
 
 sudo rsync -avz -e "ssh -i /data/git/sshkeys/cui/id_rsa  "  \
  root@bigtangle.de:/data/vm/test-sync-mysql/var/lib/mysql/2023-Jul-20_info-backup.sql.gz .  \
  
  
  docker rm -f $DBHOST 
  docker run -d  -t    -p 3306:3306   \
-v /data/vm/$DBHOST/var/lib/mysql:/var/lib/mysql  \
-e MYSQL_ROOT_PASSWORD=$DB_PASSWORD   \
-e MYSQL_DATABASE=info  --name=$DBHOST  -h $DBHOST   mysql:8.0.23 

   