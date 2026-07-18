export BIGTANGLEURL=https://p.bigtangle.org:18088/
export BIGTANGLEVERSION=2023-07-19 

docker rm -f    bigtangle-web
docker run -d  -t     \
-e BIGTANGLEURL=$BIGTANGLEURL  -e BIGTANGLEURL=$BIGTANGLEURL -e SSL=false \
 --name=bigtangle-web  -h bigtangle-web   j0904cui/bigtangle-web:$BIGTANGLEVERSION

 