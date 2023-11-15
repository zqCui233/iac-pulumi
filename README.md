# iac-pulumi

- To select stack:
```shell
pulumi stack select dev/demo
```

- To set up aws cli account: 
```shell
export AWS_PROFILE = dev/demo
```

- To build up resources:
```shell
pulumi up -y
```

- To destroy resources:
```shell
pulumi destroy -y
```