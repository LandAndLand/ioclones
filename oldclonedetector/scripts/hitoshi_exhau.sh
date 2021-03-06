#!/bin/sh
iorepos=(iorepo/cj_11 iorepo/cj_12 iorepo/cj_13 iorepo/cj_14)

read -s -p "Password: " pw

echo "Exhaustive mode"
for i in "${iorepos[@]}"
do
	comp_name=$(echo "$i"|sed "s/\///g")
	java -Xmx62g -cp target/CloneDetector-0.0.1-SNAPSHOT.jar edu.columbia.cs.psl.ioclones.driver.SimAnalysisDriver -cb code_repo/bin -alg deepHash -mode exhaustive -eName $comp_name -db ip:port/hitoshio -user root -pw $pw -io $i
done