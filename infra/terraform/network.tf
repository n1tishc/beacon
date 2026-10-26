# One public subnet for the instance and two private ones for RDS (which wants two availability
# zones). No NAT gateway: it would be the most expensive thing in the bill. The database subnets have
# no route out at all; the instance reaches AWS APIs and Slack through the internet gateway, and S3
# through a free gateway endpoint.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  vpc_cidr = "10.20.0.0/16"
  azs      = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "main" {
  cidr_block           = local.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # CloudFront reaches the instance by its public DNS name
  tags                 = { Name = "beacon" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "beacon" }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(local.vpc_cidr, 8, 0)
  availability_zone = local.azs[0]
  tags              = { Name = "beacon-public" }
}

resource "aws_subnet" "database" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(local.vpc_cidr, 8, 10 + count.index)
  availability_zone = local.azs[count.index]
  tags              = { Name = "beacon-database-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "beacon-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Image layers (ECR keeps them in S3), attachments and OS packages stay on the AWS network.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]
  tags              = { Name = "beacon-s3" }
}

# Take over the VPC's default security group with no rules, so anything launched without an explicit
# group gets no access at all.
resource "aws_default_security_group" "main" {
  vpc_id = aws_vpc.main.id
}
