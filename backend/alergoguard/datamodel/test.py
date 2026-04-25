import torch
from alergy_model import AllergyModel
from tensors.pollen_tensor import PollenTensorizer
from transforms.log_transform import LogTransform
 
SPECIES = ["birch", "grass", "weed", "oak", "pine"]
 
raw_pollen = {
    "birch": 3,
    "grass": 1,
    "weed": 0,
    "oak": 2,
    "pine": 1,
}
 
tensorizer = PollenTensorizer(SPECIES)
transform = LogTransform()
model = AllergyModel(len(SPECIES))
model.eval()
 
x = tensorizer.transform(raw_pollen).float()
x = transform(x)
 
with torch.no_grad():
    E = model.exposure(x)
    p = torch.sigmoid(model.k * (E - model.T))
 
print(f"Exposure:    {E.item():.4f}")
print(f"Probability: {p.item() * 100:.1f}%")
 

