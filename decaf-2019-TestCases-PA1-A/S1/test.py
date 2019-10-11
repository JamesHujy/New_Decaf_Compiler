import os

def get_allfile():
	output_file = []
	for root, dirs, files in os.walk('./result'):
		for file in files:
			output_file.append(file.split('.')[0])
	return output_file

output_file = get_allfile()
output_file.sort()
print(output_file)


for name in output_file:
	result_path = './result/'+name+'.result'
	output_path = './output/'+name+'.output'
	with open(result_path, 'r') as f:
		result = f.read()
	with open(output_path, 'r') as f:
		output = f.read()
	if output == result:
		print(name+' is succeeded')
	else:
		print(name+' is failed')

