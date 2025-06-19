# Deploy and Development

 
##  start with docker build and Test
```
git clone https://github.com/bigtangle/server.git
cd server
docker build -t  bigtangle  -f helper/bigtangle/Dockerfile . 
 
sh helper/testdocker.sh
 
```
 
## Production
```
git clone https://github.com/bigtangle/server.git
cd server
cd helper/divers
sh db.sh
sh bigtangle.sh
```
 
## Compiling yourself  

Make sure to have Java 17 installed on your computer.

#### compile & package
```
git clone https://github.com/bigtangle/server.git
cd server
mvn clean install

For development using the latest eclipse you can import this maven project
and start the server with
java  -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED net.bigtangle.server.ServerStart
```
