#!/usr/bin/env bash

usage()
{
	echo "step-metadata-extractor.sh [OPTIONS]"
	echo "--output-path   -> A path to write the JSON output. This parameter is optional."
	echo "--api-url       -> The base URL to use when pushing data to an API. This parameter is optional."
	echo "--jar-files     -> A comma separated list of jar files to scan"
}

# Parse the parameters
while [[ "$1" != "" ]]; do
    case $1 in
        --output-path )    		shift
        						outputPath=$1
                                ;;
        --api-url )           shift
                    apiUrl=$1
                                ;;
        --extractors )        shift
                    extractors=$1
                                ;;
        --jar-files )           shift
        						jarFiles=$1
                                ;;
        * )                     usage
                                exit 1
    esac
    shift
done

script=${BASH_SOURCE[0]}
bindir=$(cd `dirname ${script}` && pwd)
dir=$(dirname "${bindir}")

# Create the initial classPath
classPath=""
for i in $(ls ${dir}/libraries)
do
    # Add to the classPath
    classPath="${classPath}:${dir}/libraries/${i}"
done
# Add the provided jars to the classpath to make it easier to retrieve
for i in $(echo ${jarFiles} | sed "s/,/ /g")
do
    params="--jar-files ${i}"
    jarName=${i##*/}
    dirName=${jarName%.jar}

    echo "Processing ${jarName}"

    if [[ -n "${outputPath}" ]]
    then
      params="${params} --output-path ${outputPath}/${dirName}"
      mkdir -p "${outputPath}/${dirName}"
    fi

# TODO Once it has been decided how steps will be managed by jar, then add this back
#    if [[ -n "${apiUrl}" ]]
#    then
#      params="${params} --api-url ${apiUrl}"
#    fi

    if [[ -n "${extractors}" ]]
    then
      params="${params} --extractors ${extractors}"
    fi

    scala -cp "${classPath}:${i}" com.acxiom.metalus.MetadataExtractor ${params} > /dev/null 2>&1

    echo "${jarName} complete"
done
