resource "aws_ecr_repository" "openmind" {
  name                 = "openmind"
  image_tag_mutability = "IMMUTABLE"
}

resource "aws_ecr_lifecycle_policy" "retention-policy" {
  repository = aws_ecr_repository.openmind.name

  policy = <<EOF
{
    "rules": [
        {
            "rulePriority": 1,
            "description": "Only keep 3 most recent images",
            "selection": {
                "tagStatus": "untagged",
                "countType": "imageCountMoreThan",
                "countNumber": 3
            },
            "action": {
                "type": "expire"
            }
        }
    ]
}
EOF
}
