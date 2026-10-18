# The fieldrepo workspace's values, loaded by every plan and apply in this directory without a
# -var flag. Committed on purpose (a named exception in .gitignore): none of these authenticates
# anything, and a missing or wrong value makes a plan propose renaming the live estate.

project      = "fieldrepo"
bucket_name  = "fieldrepo-media-626159998512"
ssh_key_name = "fieldrepo-deploy"
