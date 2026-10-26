# One HTTPS origin for the browser, as locally with nginx: the Angular app from S3, and /api/* from
# the gateway on EC2. The API must share the app's origin because the refresh token is a
# SameSite=Strict cookie scoped to /api/auth; a separate API domain would break session restore.

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Every header (Authorization included), cookie and query string goes to the gateway.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy and friends.
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "beacon-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Client-side routes like /incidents/42 are not files in the bucket. Rewriting them here, rather than
# with distribution-wide custom error pages, keeps the API's own 403 and 404 responses intact.
resource "aws_cloudfront_function" "spa_routing" {
  name    = "beacon-spa-routing"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for client-side routes"
  publish = true
  code    = file("${path.module}/functions/spa-routing.js")
}

locals {
  web_origin = "web"
  api_origin = "api"
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  comment             = "Beacon"
  default_root_object = "index.html"
  http_version        = "http2and3"
  price_class         = "PriceClass_100" # North America and Europe edges: the cheapest class

  origin {
    origin_id                = local.web_origin
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  # Plain HTTP between CloudFront and the instance: without a domain of our own there is no
  # certificate the instance could present. Viewers always use HTTPS. See the README's tradeoffs.
  origin {
    origin_id   = local.api_origin
    domain_name = aws_instance.app.public_dns
    custom_origin_config {
      http_port              = 8080
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 30
    }
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.api_origin
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    compress                 = true
  }

  default_cache_behavior {
    target_origin_id           = local.web_origin
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
    compress                   = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_routing.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
