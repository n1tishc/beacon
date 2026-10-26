# One ECR repository per service. The deploy job pushes images tagged with the commit SHA; tags are
# immutable, so a tag always means the same build, and rolling back is deploying an older SHA.

resource "aws_ecr_repository" "service" {
  for_each             = local.services
  name                 = "beacon/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # scripts/aws-destroy.sh

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Enough to roll back a couple of releases. Beyond the 500 MB of private storage in the free tier,
# ECR costs $0.10 per GB-month: four services at three images each come to a few cents.
resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the three most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 3
      }
      action = { type = "expire" }
    }]
  })
}
