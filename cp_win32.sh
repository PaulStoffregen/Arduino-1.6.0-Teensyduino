#! /bin/sh

# copy JAR files to a windows machine for testing

ftp xp << EOT
bin
cd arduino-1.6.0/lib
lcd app/
put pde.jar
lcd ../arduino-core/
put arduino-core.jar
EOT

