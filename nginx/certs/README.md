# Your own TLS certificate

For a server on a closed campus network, where Let's Encrypt cannot reach it: put the
certificate from campus IT here as

    fullchain.pem   the certificate followed by any intermediates
    privkey.pem     its private key

nginx picks them up within a minute, without a restart. Nothing in this directory except this
file is committed (see .gitignore). Lab machines must trust the issuing CA.
