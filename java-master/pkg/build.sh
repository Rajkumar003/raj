#!/bin/bash -ex

function die {
	echo $@
	exit 1
}

cd `dirname $0`

if [[ $# -eq 3 ]]; then
	FPM_TARGET=$1
	VERSION=$2
	ITERATION=$3
elif [[ $# -eq 1 ]]; then
	FPM_TARGET=$1
	# Automatically get next version/iteration based on packagecloud and current repo.
	MOST_RECENT_VERSION=$(git tag | grep wavefront- | sed -e 's/wavefront-//' | sort -V | tail -1 | tr -d '[[:space:]]')
	YUM_VERSION=$(yum info wavefront-proxy | grep Version | cut -d: -f2 | tr -d '[[:space:]]')
	YUM_ITERATION=$(yum info wavefront-proxy | grep Release | cut -d: -f2 | tr -d '[[:space:]]')
	VERSION=$MOST_RECENT_VERSION
	if [[ "$MOST_RECENT_VERSION" == "$YUM_VERSION" ]]; then
		let "ITERATION=YUM_ITERATION+1"
	else
		ITERATION=1
	fi
else
	die "Usage: $0 <fpm_target> [<fpm_version> <fpm_iteration>]"
fi

if [[ $FPM_TARGET == "deb" ]]; then
	EXTRA_DIRS="usr"
else
	EXTRA_DIRS=""
fi

fpm \
	--after-install after-install.sh \
	--before-remove before-remove.sh \
	--after-remove after-remove.sh \
	--architecture amd64 \
	--deb-no-default-config-files \
	--deb-priority optional \
	--depends curl \
	--description "Proxy for sending data to Wavefront." \
	--exclude "*/.git" \
	--iteration $ITERATION \
	--license "Apache 2.0" \
	--log info \
	--maintainer "Wavefront <support@wavefront.com>" \
	--name wavefront-proxy \
	--rpm-os linux \
	--url https://www.wavefront.com \
	--vendor Wavefront \
	--version $VERSION \
	-C build \
	-s dir \
	-t $FPM_TARGET \
	opt etc $EXTRA_DIRS

if [[ $FPM_TARGET == "rpm" ]]; then
	rpm --delsign *.rpm
fi
