package com.georgv.audioworkstation.server

object CognitoConfig {
    const val REGION = "us-east-1"
    const val POOL_ID = "us-east-1_CaWxPoiz1"
    const val CLIENT_ID = "15je0c9k5gv0vjfek5v1fmcdf6"
    const val DOMAIN = "georg-audioworkstation.auth.us-east-1.amazoncognito.com"

    const val ISSUER = "https://cognito-idp.$REGION.amazonaws.com/$POOL_ID"
    const val JWKS_URL = "$ISSUER/.well-known/jwks.json"
    const val USER_INFO_URL = "https://$DOMAIN/oauth2/userInfo"
}
